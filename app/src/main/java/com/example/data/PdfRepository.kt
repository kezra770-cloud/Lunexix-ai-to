package com.example.data

import android.graphics.Bitmap
import android.util.Base64
import com.example.api.Content
import com.example.api.DocumentParseResult
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class PdfRepository(private val db: AppDatabase) {
    private val documentDao = db.documentDao()
    private val messageDao = db.messageDao()

    val allDocuments: Flow<List<DocumentEntity>> = documentDao.getAllDocumentsFlow()

    fun getMessagesForDocument(documentId: Int): Flow<List<MessageEntity>> =
        messageDao.getMessagesForDocumentFlow(documentId)

    suspend fun getDocumentById(id: Int): DocumentEntity? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(id)
    }

    suspend fun insertDocument(document: DocumentEntity): Long = withContext(Dispatchers.IO) {
        documentDao.insertDocument(document)
    }

    suspend fun deleteDocument(id: Int) = withContext(Dispatchers.IO) {
        documentDao.deleteDocumentById(id)
    }

    suspend fun insertMessage(message: MessageEntity): Long = withContext(Dispatchers.IO) {
        messageDao.insertMessage(message)
    }

    // Encodes a Bitmap to compact low-size Base64 JPEG string
    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Compress with high quality (but compressed) JPEG to optimize size & text legibility
        this.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // Call Gemini API to extract text and summarize PDF page images
    suspend fun analyzePdf(
        apiKey: String,
        name: String,
        bitmaps: List<Bitmap>,
        fileSize: Long
    ): DocumentEntity = withContext(Dispatchers.IO) {
        val systemInstructionText = """
            You are an expert document analysis AI. Your task is to analyze the provided page images of a PDF document.
            
            Produce a structured response in valid, raw JSON matches the following format:
            {
              "summary": "WRITE A HIGHLY READABLE CONCISE MARKDOWN SUMMARY highlighting key sections, main findings, and takeaways.",
              "extractedText": "EXTRACT ALL IMPORTANT TEXT, key numbers, lists, facts, tables, and structures from all the rendered pages. Organize it clearly with page headings. Be detailed, precise, and dense in information, preserving key technical facts so we can query this text in subsequent conversations."
            }
            
            Do NOT wrap the JSON inside markdown enclosing tags (do not put ```json ... ```), return just the raw, clean JSON string starts with '{' and ends with '}'.
        """.trimIndent()

        // Build the contents
        val parts = mutableListOf<Part>()
        bitmaps.forEachIndexed { index, bitmap ->
            parts.add(Part(text = "--- PAGE ${index + 1} ---"))
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64())))
        }
        parts.add(Part(text = "Analyze these pages and output the parsed JSON now."))

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = parts)),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.1f
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
        )

        val response = RetrofitClient.service.generateContent(apiKey, request)
        val jsonText = response.candidates?.flatMap { it.content?.parts ?: emptyList() }
            ?.firstOrNull { it.text != null }?.text
            ?: throw Exception("No response received from the document analyzer.")

        // Parse JSON using Moshi
        val adapter = RetrofitClient.moshi.adapter(DocumentParseResult::class.java)
        val parseResult = adapter.fromJson(jsonText.trim())
            ?: throw Exception("Failed to parse document analysis. Output was not valid JSON.")

        val doc = DocumentEntity(
            name = name,
            summary = parseResult.summary,
            extractedText = parseResult.extractedText,
            pageCount = bitmaps.size,
            fileSize = fileSize
        )

        val generatedId = insertDocument(doc)
        val savedDoc = doc.copy(id = generatedId.toInt())

        // Automatically create a welcoming chat summary message in the database
        insertMessage(
            MessageEntity(
                documentId = savedDoc.id,
                role = "model",
                content = "### Document Loaded Successfully!\n\nI have indexed **${savedDoc.name}** (${savedDoc.pageCount} pages).\n\nHere is a comprehensive summary of the document:\n\n${savedDoc.summary}\n\nFeel free to ask me any questions about its content!"
            )
        )

        savedDoc
    }

    // Call Gemini to answer a specific user question
    suspend fun askQuestion(
        apiKey: String,
        document: DocumentEntity,
        question: String,
        history: List<MessageEntity>
    ): String = withContext(Dispatchers.IO) {
        val systemInstructionText = """
            You are a helpful, expert AI assistant.
            You are helping the user query the following document: "${document.name}".
            
            Here is the dense indexed text from the document:
            --- START DOCUMENT TEXT ---
            ${document.extractedText}
            --- END DOCUMENT TEXT ---
            
            Guidelines:
            1. Rely STRICTLY on the document text provided above to answer the user's questions.
            2. If the user's question cannot be answered using the provided text, state that plainly and truthfully rather than making up answers.
            3. Write clear, detailed, and visually structured responses with markdown formatting (bullet points, bold texts).
            4. Keep the tone helpful, professional, and objective.
        """.trimIndent()

        // Create the alternating contents list for Gemini chat history
        val apiContents = mutableListOf<Content>()
        
        // Add existing conversation history
        history.forEach { msg ->
            apiContents.add(
                Content(
                    role = if (msg.role == "user") "user" else "model",
                    parts = listOf(Part(text = msg.content))
                )
            )
        }
        
        // Add the current user question
        apiContents.add(
            Content(
                role = "user",
                parts = listOf(Part(text = question))
            )
        )

        val request = GenerateContentRequest(
            contents = apiContents,
            systemInstruction = Content(
                parts = listOf(Part(text = systemInstructionText))
            ),
            generationConfig = GenerationConfig(temperature = 0.3f)
        )

        // Save the user's question in the database
        insertMessage(
            MessageEntity(
                documentId = document.id,
                role = "user",
                content = question
            )
        )

        val response = RetrofitClient.service.generateContent(apiKey, request)
        val replyText = response.candidates?.flatMap { it.content?.parts ?: emptyList() }
            ?.firstOrNull { it.text != null }?.text
            ?: "I'm sorry, I couldn't generate an answer of this document."

        // Save the AI's reply in the database
        insertMessage(
            MessageEntity(
                documentId = document.id,
                role = "model",
                content = replyText
            )
        )

        replyText
    }
}
