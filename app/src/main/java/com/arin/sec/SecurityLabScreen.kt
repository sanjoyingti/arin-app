@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.arin.sec

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.terminal.Linux
import kotlinx.coroutines.launch

/** One installed tool: what it does and how its command line is built. */
data class SecTool(
    val name: String,
    val binary: String,
    val category: String,
    val what: String,
    val build: (String, String) -> String,
    val needsTarget: Boolean = true,
    val hint: String = "host or IP",
)

/** The catalogue of the security tools installed in the built-in Linux, grouped by category. */
object SecCatalog {
    val categories = listOf("Network", "Web", "Passwords", "Reverse engineering", "Android", "OSINT", "Utility")
    val tools = listOf(
        SecTool("Port scan (quick)", "nmap", "Network", "Top 100 ports, service names.", { t, _ -> "nmap -Pn --top-ports 100 $t" }),
        SecTool("Port scan (full)", "nmap", "Network", "All 65535 ports.", { t, _ -> "nmap -Pn -p- $t" }),
        SecTool("Service + version scan", "nmap", "Network", "Fingerprints open services.", { t, _ -> "nmap -Pn -sV -sC $t" }),
        SecTool("Vulnerability scripts", "nmap", "Network", "nmap vuln NSE scripts.", { t, _ -> "nmap -Pn -sV --script vuln $t" }),
        SecTool("UDP top ports", "nmap", "Network", "Common UDP services.", { t, _ -> "nmap -Pn -sU --top-ports 50 $t" }),
        SecTool("Ping sweep subnet", "masscan", "Network", "Fast host discovery.", { t, _ -> "masscan $t -p1-65535 --rate 1000" }, hint = "CIDR, e.g. 192.168.1.0/24"),
        SecTool("Fingerprint site", "whatweb", "Web", "What the site runs on.", { t, _ -> "whatweb -a 3 $t" }, hint = "https://site"),
        SecTool("Detect WAF", "wafw00f", "Web", "Finds the firewall in front.", { t, _ -> "wafw00f $t" }, hint = "https://site"),
        SecTool("Nuclei scan", "nuclei", "Web", "Known-issue templates.", { t, _ -> "nuclei -u $t -silent -nc" }, hint = "https://site"),
        SecTool("Nuclei — critical only", "nuclei", "Web", "Critical/high templates only.", { t, _ -> "nuclei -u $t -severity critical,high -silent -nc" }, hint = "https://site"),
        SecTool("Nikto web scan", "nikto", "Web", "Misconfigurations and known files.", { t, _ -> "nikto -host $t -nointeractive" }, hint = "https://site"),
        SecTool("Directory brute force", "ffuf", "Web", "Hidden paths via wordlist.", { t, _ -> "ffuf -u ${t.trimEnd('/')}/FUZZ -w /root/.arin/wordlists/dirs-small.txt -mc 200,204,301,302,401,403 -s" }, hint = "https://site"),
        SecTool("Subdomain brute", "gobuster", "Web", "DNS subdomain enumeration.", { t, _ -> "gobuster dns -d $t -w /root/.arin/wordlists/subs.txt -q" }, hint = "domain"),
        SecTool("SQL injection check", "sqlmap", "Web", "Tests URL parameters.", { t, _ -> "sqlmap -u '$t' --batch --level 2 --risk 2" }, hint = "URL with ?id=1"),
        SecTool("Search exploits", "searchsploit", "Web", "Offline ExploitDB lookup.", { t, _ -> "searchsploit $t" }, hint = "software and version"),
        SecTool("Identify hash", "hashcat", "Passwords", "Names the hash type.", { t, _ -> "hashcat --identify '$t'" }, hint = "hash string"),
        SecTool("Crack hash (wordlist)", "hashcat", "Passwords", "Dictionary attack, 10k list.", { t, _ -> "echo '$t' > /tmp/h.txt && hashcat -m 0 -a 0 /tmp/h.txt /root/.arin/wordlists/common-pass.txt --quiet --potfile-disable 2>&1 | tail -20" }, hint = "MD5 hash"),
        SecTool("Hydra SSH login", "hydra", "Passwords", "Brute force SSH logins.", { t, _ -> "hydra -L /root/.arin/wordlists/common-pass.txt -P /root/.arin/wordlists/common-pass.txt $t ssh" }, hint = "IP or host"),
        SecTool("Analyse binary", "r2", "Reverse engineering", "radare2 report on a binary.", { t, _ -> "r2 -A -q -c 'iI; izz~http; afl~main; q' $t" }, hint = "path in /root/files"),
        SecTool("Extract embedded files", "binwalk", "Reverse engineering", "Archives/firmware in a file.", { t, _ -> "binwalk -e $t" }, hint = "path in /root/files"),
        SecTool("Strings in binary", "strings", "Reverse engineering", "Readable text in a file.", { t, _ -> "strings -n 6 $t | head -200" }, hint = "path in /root/files"),
        SecTool("File metadata", "exiftool", "Reverse engineering", "EXIF, author, GPS.", { t, _ -> "exiftool $t" }, hint = "path in /root/files"),
        SecTool("Hidden data in image", "steghide", "Reverse engineering", "Steganography check.", { t, _ -> "steghide info $t -p '' || steghide extract -sf $t -p '' -f" }, hint = "path in /root/files"),
        SecTool("Decode APK", "apktool", "Android", "Unpacks resources and smali.", { t, _ -> "N=\$(basename '$t' .apk); apktool d -f '$t' -o /root/files/rea/\$N && echo DECODED=/root/files/rea/\$N" }, hint = "/root/files/app.apk"),
        SecTool("Decompile to Java", "jadx", "Android", "Readable Java from the APK.", { t, _ -> "N=\$(basename '$t' .apk); jadx -d /root/files/rea/\$N-jadx '$t' && echo OUT=/root/files/rea/\$N-jadx" }, hint = "/root/files/app.apk"),
        SecTool("Find secrets in APK", "apkleaks", "Android", "Keys, tokens, URLs.", { t, _ -> "apkleaks -f '$t'" }, hint = "/root/files/app.apk"),
        SecTool("APK permissions + cert", "aapt2", "Android", "Manifest and signing info.", { t, _ -> "unzip -l '$t' | head -40; exiftool '$t'" }, hint = "/root/files/app.apk"),
        SecTool("Whois lookup", "whois", "OSINT", "Domain registration owner.", { t, _ -> "whois $t" }, hint = "domain"),
        SecTool("DNS records", "dig", "OSINT", "All record types.", { t, _ -> "dig +short A $t; dig +short MX $t; dig +short TXT $t; dig +short NS $t" }, hint = "domain"),
        SecTool("Subdomains via crt.sh", "curl", "OSINT", "Certificate log search.", { t, _ -> "curl -s 'https://crt.sh/?q=%25.$t&output=json' | python3 -c 'import json,sys;d=json.load(sys.stdin);print(\"\\n\".join(sorted({n for r in d for n in r[\"name_value\"].splitlines()})))' 2>/dev/null | head -60" }, hint = "domain"),
        SecTool("Username search", "sherlock", "OSINT", "Account name across sites.", { t, _ -> "sherlock $t --timeout 8 --print-found 2>&1 | tail -60" }, hint = "username"),
        SecTool("Email accounts", "holehe", "OSINT", "Sites registered with an email.", { t, _ -> "holehe $t 2>&1 | tail -60" }, hint = "email"),
        SecTool("Capture packets", "tcpdump", "Utility", "20 s pcap capture + summary.", { t, _ -> "timeout 20 tcpdump -i any -nn -c 100 -w /root/files/capture.pcap 2>&1 | tail -5; tcpdump -nn -r /root/files/capture.pcap 2>/dev/null | head -40" }, needsTarget = false),
        SecTool("Update all tools", "apt", "Utility", "Newer versions of every tool.", { _, _ -> "apt-get update -qq && apt-get install -y --only-upgrade nmap masscan nikto gobuster sqlmap hydra john hashcat binwalk steghide libimage-exiftool-perl radare2 tcpdump whatweb 2>&1 | tail -20" }, needsTarget = false),
    )

    fun byCategory(c: String) = tools.filter { it.category == c }
}

/**
 * One-tap lab over the tools installed in the built-in Linux: enter a target, tap Run on a tool, and the
 * command runs with its output streamed here and a copy of it saved under /root/.arin/sec/reports.
 */
@Composable
fun SecurityLabScreen(vm: com.arin.chat.ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }

    suspend fun launchTool(tool: SecTool) {
        if (running != null) return
        val t = target.trim()
        if (tool.needsTarget && t.isBlank()) { output = "Enter a ${tool.hint} first."; return }
        running = tool.name
        output = "▶ ${tool.name}…\n"
        val cmd = tool.build(t, "")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val report = "/root/.arin/sec/reports/${stamp}-${tool.binary}"
        val r = Linux.run(context, "mkdir -p /root/.arin/sec/reports; ($cmd) 2>&1 | tee '$report'.txt | tail -400", timeoutMs = 20 * 60_000L) { chunk ->
            scope.launch { output = (output + chunk).takeLast(60_000) }
        }
        val extra = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout)
            if (r.stderr.isNotBlank()) append("\n").append(r.stderr)
            r.error?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }
        output = (output + "\n" + extra + "\n— exit ${r.exitCode} · saved ${report}.txt").takeLast(60_000)
        running = null
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Security Lab") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Target") },
                placeholder = { Text("host, URL, domain, username or file path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (running != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Running $running…", style = MaterialTheme.typography.labelMedium)
            } else {
                Text(
                    "${SecCatalog.tools.size} tools ready · results saved to /root/.arin/sec/reports",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (output.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        output,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    SecCatalog.categories.forEach { cat ->
                        val list = SecCatalog.byCategory(cat)
                        if (list.isEmpty()) return@forEach
                        item { Text(cat, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                        items(list) { tool ->
                            ListItem(
                                headlineContent = { Text(tool.name) },
                                supportingContent = { Text(tool.what, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { TextButton(onClick = { scope.launch { launchTool(tool) } }) { Text("Run") } },
                            )
                        }
                    }
                }
            }
            if (output.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    TextButton(onClick = { output = "" }) { Text("Tools list") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, output.take(100_000))
                        }
                        context.startActivity(android.content.Intent.createChooser(i, "Share result"))
                    }) { Text("Share") }
                }
            }
        }
    }
}
