import Foundation

/// Finds a one-time code in a notification, e.g. "482913 is your OTP" or "G-482913".
/// Requires a keyword like "OTP"/"code" so ordinary numbers in messages aren't copied,
/// and skips amounts ("Rs 5000") and masked card numbers ("XX1234").
enum OTP {
    private static let keyword = try! NSRegularExpression(
        pattern: #"\b(otp|code|passcode|password|pin|verification|verify|one[- ]?time|2fa|log ?in|sign[- ]?in|authenticat\w*)\b"#,
        options: [.caseInsensitive])

    // 4–8 digits, or "123 456" / "123-456". Not part of a longer word/number, decimal or amount.
    private static let candidate = try! NSRegularExpression(
        pattern: #"(?<![\w.,₹$€£])(\d{3}[- ]\d{3}|\d{4,8})(?![\w,/-]|\.\d)"#)

    private static let currencyBefore = try! NSRegularExpression(
        pattern: #"(rs\.?|inr|usd|eur|gbp|₹|\$|€|£)\s*$"#, options: [.caseInsensitive])

    static func find(in text: String) -> String? {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        let keywords = keyword.matches(in: text, range: full).map(\.range.location)
        guard !keywords.isEmpty else { return nil }

        let codes = candidate.matches(in: text, range: full).compactMap { match -> (code: String, distance: Int)? in
            let start = match.range.location
            let windowStart = max(0, start - 6)
            let before = NSRange(location: windowStart, length: start - windowStart)
            if currencyBefore.firstMatch(in: text, range: before) != nil { return nil }
            let digits = ns.substring(with: match.range).filter(\.isNumber)
            let distance = keywords.map { abs($0 - start) }.min()!
            return (digits, distance)
        }
        // The code is usually right next to "OTP"/"code"; prefer the closest number.
        return codes.min { $0.distance < $1.distance }?.code
    }
}
