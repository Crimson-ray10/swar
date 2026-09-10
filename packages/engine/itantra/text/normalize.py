"""Text normalization and critical-span extraction."""

import re
import unicodedata
from typing import List
from itantra.semantic.schema import CriticalSpan

# Negation words across supported languages
NEGATION_WORDS = {
    "hi": ["नहीं", "ना", "मत", "नाहीं"],
    "ta": ["இல்லை", "கூடாது", "வேண்டாம்", "அல்ல"],
    "en": ["not", "no", "never", "don't", "cannot", "can't", "won't", "neither", "nor"],
    "bn": ["না", "নয়", "নেই"],
    "te": ["లేదు", "వద్దు", "కాదు"],
    "mr": ["नाही", "नको", "नव्हे"],
    "gu": ["નથી", "નહીં"],
    "kn": ["ಇಲ್ಲ", "ಬೇಡ", "ಅಲ್ಲ"],
    "ml": ["ഇല്ല", "വേണ്ട", "അല്ല"],
    "pa": ["ਨਹੀਂ", "ਨਾ"],
}


def normalize_text(text: str, language: str = "hi") -> str:
    """Normalize text into canonical Unicode NFC with cleaned whitespace."""
    if not text:
        return ""
    normalized = unicodedata.normalize("NFC", text)
    # Collapse multiple whitespace characters
    normalized = re.sub(r"\s+", " ", normalized).strip()
    return normalized


def extract_critical_spans(text: str, language: str = "hi") -> List[CriticalSpan]:
    """
    Extract critical entities and literals that must be protected with absolute priority:
    - Digits, phone numbers, pin codes, currency amounts
    - Negation operators
    - Dates / times
    """
    spans: List[CriticalSpan] = []

    # 1. Digits / Numbers / Amounts / Phone numbers
    number_pattern = re.compile(r"(\+?\d{1,4}[-.\s]?)?(\(?\d{3}\)?[-.\s]?)?[\d]{3,}[-.\s]?\d{3,}|₹?\d+(?:,\d+)*(?:\.\d+)?")
    for match in number_pattern.finditer(text):
        raw_literal = match.group()
        literal = raw_literal.strip()
        if any(c.isdigit() for c in literal):
            start = match.start() + (len(raw_literal) - len(raw_literal.lstrip()))
            end = start + len(literal)
            span_type = "phone" if len(re.sub(r"\D", "", literal)) >= 10 else "digit"
            spans.append(
                CriticalSpan(
                    span_type=span_type,
                    start_char=start,
                    end_char=end,
                    literal=literal,
                )
            )

    # 2. Negations (crucial to avoid inverted command meanings in semantic communication)
    negations = NEGATION_WORDS.get(language, NEGATION_WORDS["hi"])
    for word in negations:
        # Indic scripts don't match ASCII \b; use whitespace and punctuation boundaries
        pattern = re.compile(rf"(?:^|[\s,।\.!?])({re.escape(word)})(?=$|[\s,।\.!?])", re.IGNORECASE)
        for match in pattern.finditer(text):
            spans.append(
                CriticalSpan(
                    span_type="negation",
                    start_char=match.start(1),
                    end_char=match.end(1),
                    literal=match.group(1),
                )
            )

    # Sort spans by start_char
    spans.sort(key=lambda s: s.start_char)
    return spans
