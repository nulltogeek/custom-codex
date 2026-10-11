# Answering interactive questions

Codex questions appear during work even though ordinary progress commentary is
hidden. Tap **Answer questions**, select an option or **Write your own answer**,
and tap **Submit answer**. Questions without options show a text box. Every
question needs an explicit answer; no suggested option is submitted automatically.

Asynchronous questions arrive in `agentMessage` items with `delivery: "async"`
and `questions: [{title, options?}]`. They are not server approval requests.
Their answers are sent as a normal user message containing each question and its
answer. The app reads live history before sending: it uses `turn/steer` with the
current `expectedTurnId` while the same task runs, or `turn/start` after that task
finishes. These answers bypass the normal message queue. Answering an earlier
question while another task runs is blocked. Mode and access settings apply when
starting a new turn. Existing composer drafts, attachments and queues are preserved.

Blocking `item/tool/requestUserInput` requests use the same controls and return
`{answers: {questionId: {answers: [answer]}}}` through `bridge/respond`.
Pending requests appear individually in the library and above the conversation,
so scrolling into earlier messages does not hide a blocking input request.
Secret fields use password input and are omitted from saved answer state.

A failed submission retains the answers in the open dialog and displays its error.
Disconnected submissions are disabled. If asynchronous delivery cannot be
confirmed, refresh and inspect chat history before trying again; the app never
automatically retries a message mutation. Accepted answers show **Answer sent**;
matching replies in reloaded history also mark the question answered.

Protocol fields were checked against schemas generated locally by the running
backend executable, Codex 0.159.2, with
`codex app-server generate-json-schema --experimental`. No new host endpoint or
service restart is required for answering questions. Older hosts must emit these
question fields for asynchronous controls to appear.
