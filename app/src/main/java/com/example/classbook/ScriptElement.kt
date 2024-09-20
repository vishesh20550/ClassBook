package com.example.classbook


data class ScriptElement(
    val type: ElementType,
    val content: String,
    var words: List<String> = emptyList(),
    var currentWordIndex: Int = 0
)


enum class ElementType {
    HEADING,
    SUBHEADING,
    PERIOD_BREAK,
    TEACHER_SPEECH,
    OPERATION,
    WAIT_FOR_RESPONSE,
    OTHER
}


