package dev.nulltogeek.codexremote

enum class SyntaxKind { Keyword, String, Comment, Number, Function, Type, Property, Operator, Variable, Tag, Added, Removed, Heading }
data class SyntaxToken(val start: Int, val end: Int, val kind: SyntaxKind)

private val aliases = mapOf("kt" to "kotlin", "kts" to "kotlin", "py" to "python", "js" to "javascript",
    "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "sh" to "shell", "bash" to "shell",
    "zsh" to "shell", "terminal" to "shell", "yml" to "yaml", "htm" to "html", "xml" to "html",
    "rs" to "rust", "c++" to "cpp", "cs" to "csharp", "rb" to "ruby", "patch" to "diff")
private val keywords = mapOf(
    "kotlin" to "fun val var class interface object data sealed enum package import return if else when for while do in is as try catch finally throw null true false public private internal protected override open suspend inline companion constructor this super out by",
    "python" to "def class import from as return if elif else for while in is not and or pass break continue try except finally raise with lambda yield global nonlocal True False None async await match case",
    "javascript" to "const let var function class extends new return if else switch case for while do break continue import export default from async await try catch finally throw typeof instanceof in of this super true false null undefined",
    "typescript" to "const let var function class extends new return if else switch case for while do break continue import export default from async await try catch finally throw typeof instanceof in of this super true false null undefined interface type enum implements public private protected readonly declare as keyof infer satisfies",
    "shell" to "if then else elif fi for do done while until case esac in function select local export readonly return exit source",
    "sql" to "SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER ON AS AND OR NOT NULL INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE DROP ALTER INDEX GROUP BY ORDER HAVING LIMIT OFFSET DISTINCT UNION ALL CASE WHEN THEN ELSE END WITH TRUE FALSE ASC DESC EXISTS PRIMARY KEY REFERENCES",
    "json" to "true false null",
    "yaml" to "true false null yes no on off",
    "css" to "important inherit initial unset auto none var calc rgb rgba hsl hsla media supports keyframes",
    "rust" to "fn let mut const struct enum impl trait use mod pub return if else match for while loop in as async await move ref self Self super crate where unsafe extern type true false",
    "go" to "package import func var const type struct interface return if else switch case for range go defer select chan map make new nil true false",
    "java" to "package import class interface enum extends implements public private protected static final void return if else switch case for while new this super try catch finally throw throws null true false synchronized abstract",
    "c" to "include define ifdef ifndef endif int char float double void struct enum typedef const static extern return if else switch case for while do break continue sizeof null",
    "cpp" to "include define ifdef ifndef endif int char float double void struct enum typedef const static extern return if else switch case for while do break continue sizeof class namespace using template typename public private protected virtual override auto new delete nullptr true false",
    "csharp" to "using namespace class interface struct enum public private protected internal static readonly const var void return if else switch case for foreach while new this base try catch finally throw null true false async await get set record",
    "ruby" to "def class module end require include return if elsif else unless for while do begin rescue ensure raise yield nil true false self attr_reader attr_accessor",
    "swift" to "func let var class struct enum protocol extension import return if else switch case for while in guard defer do try catch throw nil true false self init public private override async await"
).mapValues { (_, value) -> value.split(' ').toSet() }

/** Fenced language controls comment rules; strings/comments consume their contents
 * before keywords, so URLs, CSS colors and quoted source are not misclassified. */
fun syntaxTokens(code: String, language: String): List<SyntaxToken> {
    val name = language.trim().substringBefore(' ').lowercase().let { aliases[it] ?: it }
    if (name == "diff") return Regex("(?m)^.*$").findAll(code).mapNotNull { line ->
        val kind = when {
            line.value.startsWith("diff ") || line.value.startsWith("@@") || line.value.startsWith("--- ") || line.value.startsWith("+++ ") -> SyntaxKind.Heading
            line.value.startsWith('+') -> SyntaxKind.Added
            line.value.startsWith('-') -> SyntaxKind.Removed
            else -> null
        }
        kind?.let { SyntaxToken(line.range.first, line.range.last + 1, it) }
    }.toList()
    if (name !in keywords && name != "html") return emptyList()
    val pattern = compileSyntaxPattern(syntaxPattern(name)) ?: return emptyList()
    val words = keywords[name].orEmpty()
    return pattern.findAll(code).mapNotNull { match ->
        var after = match.range.last + 1
        while (after < code.length && code[after].isWhitespace()) after++
        val next = code.getOrNull(after)
        val kind = when {
            match.groups["comment"] != null -> SyntaxKind.Comment
            match.groups["string"] != null -> if (name == "json" && next == ':') SyntaxKind.Property else SyntaxKind.String
            match.groups["number"] != null || match.groups["color"] != null -> SyntaxKind.Number
            match.groups["variable"] != null -> SyntaxKind.Variable
            match.groups["tag"] != null -> SyntaxKind.Tag
            match.groups["operator"] != null -> SyntaxKind.Operator
            match.value in words || (name == "sql" && match.value.uppercase() in words) -> SyntaxKind.Keyword
            (name in listOf("css", "yaml") && next == ':') || (name == "html" && next == '=') -> SyntaxKind.Property
            next == '(' -> SyntaxKind.Function
            match.value.first().isUpperCase() && name !in listOf("shell", "html", "css", "sql") -> SyntaxKind.Type
            else -> null
        }
        kind?.let { SyntaxToken(match.range.first, match.range.last + 1, it) }
    }.toList()
}

/** ICU on Android requires literal closing braces to be escaped. */
internal fun syntaxPattern(name: String): String {
    val comments = when (name) {
        "python", "ruby", "yaml" -> "#[^\\n]*"
        "shell" -> "(?<!\\S)#[^\\n]*"
        "sql" -> "--[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)"
        "html" -> "<!--[\\s\\S]*?(?:-->|$)"
        "css" -> "/\\*[\\s\\S]*?(?:\\*/|$)"
        "json" -> "(?!)"
        else -> "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)"
    }
    val tripleStrings = if (name == "python") "\"\"\"[\\s\\S]*?(?:\"\"\"|$)|'''[\\s\\S]*?(?:'''|$)|" else ""
    val strings = tripleStrings + "\"(?:\\\\.|[^\"\\\\])*?(?:\"|$)|'(?:\\\\.|''|[^'\\\\])*?(?:'|$)" +
        if (name in listOf("javascript", "typescript", "shell")) "|`(?:\\\\.|[^`\\\\])*?(?:`|$)" else ""
    val variable = if (name == "shell") "\\$\\{[^}]*\\}|\\$[A-Za-z_][\\w]*|\\$[0-9?#@!]" else "(?!)"
    val tag = if (name == "html") "</?[A-Za-z][\\w:-]*|/?>" else "(?!)"
    val color = if (name == "css") "#[0-9a-fA-F]{3,8}\\b" else "(?!)"
    val word = if (name in listOf("css", "yaml", "html")) "[A-Za-z_][\\w-]*" else "[A-Za-z_][\\w]*"
    return "(?<comment>$comments)|(?<string>$strings)|(?<variable>$variable)|(?<tag>$tag)|(?<color>$color)|(?<number>\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)(?:[uUlLfF]+)?\\b)|(?<word>$word)|(?<operator>[+*/%=!<>|&^~?:-]+)"
}

/** Highlighting is optional: unsupported patterns must leave readable plain code. */
internal fun compileSyntaxPattern(pattern: String): Regex? = try { Regex(pattern) }
catch (_: java.util.regex.PatternSyntaxException) { null }
