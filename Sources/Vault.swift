import Foundation
import Security

enum Vault {
    private static func query(_ provider: Provider) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Bundle.main.bundleIdentifier ?? "CallCaptions",
         kSecAttrAccount as String: provider.rawValue]
    }

    static func read(_ provider: Provider) throws -> String {
        var request = query(provider)
        request[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data else {
            throw CaptionError("无法读取钥匙串（\(status)）。请解锁 iPhone 后重试。")
        }
        return String(decoding: data, as: UTF8.self)
    }

    static func save(_ text: String, for provider: Provider) throws {
        let query = query(provider)
        let data = Data(text.utf8)
        let status: OSStatus
        if text.isEmpty {
            status = SecItemDelete(query as CFDictionary)
            if status == errSecItemNotFound { return }
        } else {
            let values: [String: Any] = [kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
            let update = SecItemUpdate(query as CFDictionary, values as CFDictionary)
            status = update == errSecItemNotFound
                ? SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil) : update
        }
        if status != errSecSuccess { throw CaptionError("无法保存密钥（\(status)）。") }
    }
}
