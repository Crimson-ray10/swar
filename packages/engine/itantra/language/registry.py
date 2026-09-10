"""Language registry and specification loader for iTantra."""

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional
import yaml


@dataclass(frozen=True)
class LanguageSpec:
    id: str
    name: str
    native_name: str
    script: str
    unicode_range: List[int]
    mvp: bool
    asr_provider: str
    asr_lang_code: str
    tts_provider: str
    tts_lang_code: str
    normalizer: str
    sample_text: str


class LanguageRegistry:
    def __init__(self, config_path: str = "configs/languages.yaml"):
        self._languages: Dict[str, LanguageSpec] = {}
        self._load(config_path)

    def _load(self, config_path: str) -> None:
        p = Path(config_path)
        if not p.exists():
            raise FileNotFoundError(f"Language config not found: {config_path}")

        with open(p, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        for lang_id, info in data.get("languages", {}).items():
            self._languages[lang_id] = LanguageSpec(
                id=info["id"],
                name=info["name"],
                native_name=info["native_name"],
                script=info["script"],
                unicode_range=info["unicode_range"],
                mvp=info.get("mvp", False),
                asr_provider=info["asr_provider"],
                asr_lang_code=info["asr_lang_code"],
                tts_provider=info["tts_provider"],
                tts_lang_code=info["tts_lang_code"],
                normalizer=info["normalizer"],
                sample_text=info["sample_text"],
            )

    def get(self, lang_id: str) -> Optional[LanguageSpec]:
        return self._languages.get(lang_id)

    def list_all(self, enabled_only: bool = False) -> List[LanguageSpec]:
        if enabled_only:
            return [l for l in self._languages.values() if l.mvp]
        return list(self._languages.values())

    def numeric_id_for(self, lang_id: str) -> int:
        """Map language code to a 1-byte wire identifier."""
        ordered = ["hi", "ta", "en", "bn", "te", "mr", "gu", "kn", "ml", "pa"]
        if lang_id in ordered:
            return ordered.index(lang_id) + 1
        return 0xFF

    def lang_id_from_numeric(self, num: int) -> str:
        """Map 1-byte wire identifier back to language code."""
        ordered = ["hi", "ta", "en", "bn", "te", "mr", "gu", "kn", "ml", "pa"]
        if 1 <= num <= len(ordered):
            return ordered[num - 1]
        return "hi"  # fallback default
