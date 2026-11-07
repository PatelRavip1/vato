# Vato 10-Minute Bilingual Live Voice Call Stress Test Report

- **Date/Timestamp**: 2026-11-03 22:45:02
- **Target Duration**: 600s (10 min)
- **Actual Duration**: 617.6s
- **Total Turns**: 26
- **Success Rate**: 65.4%
- **Zero Fatal Crashes / ANRs**: PASSED

## Latency Profile

| Component | Language | Count | Min (ms) | Avg (ms) | P95 (ms) | Max (ms) |
|---|---|---|---|---|---|---|
| SenseVoice STT | en-IN | 10 | 24.4 | 26.8 | 29.3 | 29.3 |
| LLM Generation | en-IN | 10 | 4331.4 | 9922.8 | 15004.9 | 15004.9 |
| LLM Generation | gu-IN | 7 | 547.4 | 10443.7 | 26066.0 | 26066.0 |
| Complete Turn | Bilingual | 17 | 5914.0 | 16174.6 | 31408.4 | 31408.4 |

## Memory Profile (Android Dumpsys Meminfo)

- Initial PSS: 85389 KB | Final PSS: 83620 KB (Delta: -1769 KB)
- Initial RSS: 0 KB | Final RSS: 0 KB (Delta: +0 KB)

## Turn Log Excerpt (First 5 & Last 5 Turns)

| # | Time (s) | Lang | User Prompt | LLM Latency | Reply Snippet |
|---|---|---|---|---|---|
| 1 | 3.1 | en-IN | Hello! How are you doing today? | 4331.4ms | Hey! How can I help you today? |
| 2 | 13.9 | en-IN | What is the typical climate like in | 4760.1ms | Looks like your message cut off again! What were you about t |
| 3 | 25.2 | gu-IN | કેમ છો? ગાંધી આશ્રમ વિશે ટૂંકમાં જણ | 2241.4ms | In November, Gujarat transitions from the post-monsoon perio |
| 4 | 34.3 | gu-IN | ત્યાં પહોંચવા માટે નજીકનું રેલ્વે સ | 547.4ms | હું મજામાં છું!  **સાબરમતી આશ્રમ (ગાંધી આશ્રમ)** વિશે મુખ્ય  |
| 5 | 41.2 | gu-IN | અમદાવાદમાં ફરવા માટે બીજા કયા જાણીત | Nonems |  |
| 22 | 533.2 | gu-IN | મોઢેરાનું સૂર્યમંદિર કઈ સ્થાપત્ય શૈ | 7769.9ms | મોઢેરાનું સૂર્યમંદિર **મારુ-ગુર્જર સ્થાપત્ય શૈલી** (જેને **સ |
| 23 | 547.3 | gu-IN | કચ્છના રણોત્સવની વિશેષતા શું છે? | 11169.5ms | કચ્છનો **રણોત્સવ** એ સફેદ રણ (White Rann) ની કુદરતી સુંદરતા  |
| 24 | 564.9 | en-IN | Explain how solar parks in Charanka | 9901.2ms | Gujarat hosts two of India’s most critical renewable energy  |
| 25 | 581.8 | en-IN | What makes Lothal an important site | 10326.0ms | **Lothal** (located near the Gulf of Khambhat in Gujarat) is |
| 26 | 598.7 | en-IN | Tell me about the craftsmanship beh | 12436.0ms | **Patan Patola** is an exquisite, centuries-old textile art  |
