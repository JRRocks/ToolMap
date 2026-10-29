// Port of core/src/test/kotlin/fr/toolmap/core/settings/SettingsSearchTest.kt. Places are UTF-16 offsets, as Kotlin's.

import Testing
@testable import ToolMapCore

/// Kotlin `text.substring(range)`, the range in UTF-16 code units.
private func substring(_ text: String, _ range: ClosedRange<Int>) -> String {
    String(decoding: Array(text.utf16)[range], as: UTF16.self)
}

struct SettingsSearchTests {
    @Test("the words typed are folded and lose their plural mark")
    func terms() {
        #expect(SettingsSearch.terms("  Repères   TRACES ") == ["repere", "trace"])
        #expect(SettingsSearch.terms("cap") == ["cap"])
        #expect(SettingsSearch.terms("GPS") == ["gps"])
        #expect(SettingsSearch.terms("mils") == ["mil"])
        #expect(SettingsSearch.terms("niveaux") == ["niveau"])
        #expect(SettingsSearch.terms("   ").isEmpty)
    }

    @Test("every word must be in the title, the explanation or an option")
    func everyWord() {
        let texts = ["Unité de cap", "Degrés (360° par tour)", "Mils OTAN (6 400 par tour)"]
        #expect(SettingsSearch.matches(SettingsSearch.terms("cap"), texts))
        #expect(SettingsSearch.matches(SettingsSearch.terms("unite otan"), texts))
        #expect(SettingsSearch.matches(SettingsSearch.terms("DEGRES"), texts))
        #expect(!SettingsSearch.matches(SettingsSearch.terms("cap pente"), texts))
        #expect(!SettingsSearch.matches([], texts))
    }

    @Test("a plural typed finds the singular, a singular finds the plural")
    func plurals() {
        #expect(SettingsSearch.matches(SettingsSearch.terms("gants"), ["Mode gants"]))
        #expect(SettingsSearch.matches(SettingsSearch.terms("gant"), ["Mode gants"]))
        #expect(SettingsSearch.matches(SettingsSearch.terms("traces"), ["Style de la trace"]))
    }

    @Test("the bold goes on the word found, in the text with its accents")
    func boldOnTheWord() {
        let text = "Affichage du cap GNSS"
        #expect(SettingsSearch.highlights(text, SettingsSearch.terms("cap")) == [13 ... 15])
        #expect(substring(text, 13 ... 15) == "cap")
        let accents = "Écart toléré"
        #expect(SettingsSearch.highlights(accents, SettingsSearch.terms("ecart TOLERE")) == [0 ... 4, 6 ... 11])
    }

    @Test("each occurrence is in bold, and ranges that touch are merged")
    func occurrencesMerged() {
        #expect(SettingsSearch.highlights("cap et cap", SettingsSearch.terms("cap")) == [0 ... 2, 7 ... 9])
        #expect(SettingsSearch.highlights("nordet", ["nord", "rdet"]) == [0 ... 5])
        #expect(SettingsSearch.highlights("Thème", SettingsSearch.terms("cap")).isEmpty)
    }

    @Test("the folded text keeps the length of the text")
    func foldedLength() {
        let text = "Élévation, œil, Ça"
        #expect(SettingsSearch.fold(text).utf16.count == text.utf16.count)
        #expect(SettingsSearch.fold(text) == "elevation, œil, ca")
    }
}
