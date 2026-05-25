package com.example.data

import androidx.room.*

@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val username: String,
    val passwordHash: String,
    val salt: String,
    val createdAt: Long = System.currentTimeMillis()
)
