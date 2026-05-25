package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val summary: String,
    val extractedText: String,
    val pageCount: Int,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis()
)
