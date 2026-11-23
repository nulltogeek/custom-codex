# Android drawer and conversation layout

The Android shell now uses a slide-out drawer for chat history, projects, archives, settings, and administrator user management. A compact header keeps navigation, chat settings/model choices, a new-chat action, and the current chat's options available while reading a conversation.

The default light/dark palettes are neutral white and charcoal. The prior saved `exchange` accent value now means the neutral default; optional accent selections remain available in Settings. User messages use right-aligned rounded gray bubbles. Assistant messages use the full conversation width, with copy/edit actions below message content. Sender labels have been removed from ordinary messages. Commands and file changes retain their expandable detail panels.

The floating composer uses a plus menu for attachments and a round up-arrow Send action. Running work retains a separate Stop control and the existing message queue. Mode, model, access, and reasoning controls are available by tapping the Codex dropdown in the conversation header. No unsupported voice controls have been added.

Visual references were the public ChatGPT Android Google Play screenshots at https://play.google.com/store/apps/details?id=com.openai.chatgpt, inspected on November 20, 2026. This app remains independently branded as Codex Remote. The screenshots serve as layout references; exact equivalence across Android versions, screen sizes, and ChatGPT releases is not asserted.

Navigation tests use `open-navigation`, `navigation-drawer`, `nav-chats`, `nav-archived`, `nav-projects`, `nav-settings`, and (administrators only) `nav-users`. The drawer also exposes `drawer-new-chat`, `drawer-search`, `drawer-chat:<thread ID>`, and `drawer-project:<project ID>` test tags. Conversation copy/edit, attachment, stop/send, question, command, and history tags remain available.
