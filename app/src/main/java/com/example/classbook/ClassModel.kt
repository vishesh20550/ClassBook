package com.example.classbook

data class ClassModel(
    val id: String = "",
    var name: String = "",
    var pdfId: String = "",
    var scriptId: String = "",
    var users: MutableList<String> = mutableListOf()
)

