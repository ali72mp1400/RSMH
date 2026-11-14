package app.baleoqab

object DnsConfigGenerator {

    fun generateDnsRules(customDns: String): List<String> {
        val dnsList = mutableListOf<String>()
        
        val trimmedDns = customDns.trim()
        if (trimmedDns.isNotEmpty()) {
            dnsList.add(trimmedDns)
        }
        
        // افزودن DNSهای پیش‌فرض و ایمن جایگزین به عنوان پشتیبان
        if (!dnsList.contains("1.1.1.1")) {
            dnsList.add("1.1.1.1")
        }
        if (!dnsList.contains("8.8.8.8")) {
            dnsList.add("8.8.8.8")
        }
        
        return dnsList
    }
}
