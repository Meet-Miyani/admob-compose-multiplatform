package dev.avinya.admob.showcase.domain.article

/** Article bodies store paragraphs separated by a blank line. */
fun splitParagraphs(body: String): List<String> =
    body.split("\n\n").map(String::trim).filter(String::isNotEmpty)
