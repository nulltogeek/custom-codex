# Response formatting and message input

Your message bubbles sit on the right, and Codex answers sit on the left. These
positions remain physical right/left in right-to-left app layouts. Text keeps its
natural reading direction.

Responses use 16 sp text with 26 sp line height, more space between blocks and
clearer heading sizes. Lists have separate markers and hanging indentation, with
nested items, continuation lines and task indicators. Consecutive quote lines
share a single callout. Tables use aligned, wrapping columns, a shaded header
and horizontal scrolling on narrow screens. Escaped pipes and pipes inside inline
code remain in their cell. Inline bold, italic, code and links are retained, with
strikethrough support added.

Fenced code uses language-aware highlighting with distinct colors for keywords,
strings, comments, numbers, function calls, types, properties, variables and tags.
Keywords have stronger weight; comments are italic. Diff blocks distinguish
headers, additions and removals. Palettes adapt to the app's dark/light theme.
Code uses 14 sp text with 22 sp line height. Collapse/expand and horizontal scrolling
remain available, and Copy code copies the complete original source.

Supported languages include Kotlin, Python, JavaScript, TypeScript, shell, SQL,
JSON, YAML, CSS, HTML/XML, Rust, Go, Java, C, C++, C#, Ruby, Swift and diff.
Common fence aliases such as `kt`, `py`, `js`, `ts`, `bash`, `sh`, `yml` and `patch`
are recognized. Highlighting is lexical rather than a full grammar parser;
unknown fence languages are shown as plain code.
Shell expansion patterns escape literal braces for Android's ICU regex engine.
If a highlighting rule cannot compile, the code stays readable without colors
instead of closing the chat. `scripts/verify-syntax-icu.py` checks the built rules
against native ICU, since desktop Java accepts some patterns Android rejects.

In the composer, **Enter** or **Numpad Enter** sends once. **Shift+Enter** inserts a
new line at the cursor, replacing selected text if any. Shift+Numpad Enter does
the same. Newline insertion is handled explicitly and stays available while Send
is disabled. The software keyboard requests the **Send** action. Pasted multiline
text remains a draft. All send actions use the same send callback and readiness
checks as the Send button, including connection, uploads and uncertain delivery.
If work is already running, Enter queues the message just like the Send button.
