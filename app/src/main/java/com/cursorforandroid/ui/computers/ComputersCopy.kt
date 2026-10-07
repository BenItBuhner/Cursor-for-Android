package com.cursorforandroid.ui.computers

/** Words the Computers screens and the Settings row share, so tests can pin them. */
object ComputersCopy {
    const val TITLE = "Computers"
    const val SETTING_DETAIL = "Control agents that stay on a computer."
    const val HEADER = "Computers"
    const val EMPTY = "No computers on this account yet. Open Cursor on a computer while signed in to the same account."
    const val PAIR = "Request pairing"
    const val APPROVE = "Approve this pairing in the Cursor desktop app on this computer."
    const val OFFLINE = "This computer is offline. Keep Cursor open (and awake, if you use that setting)."
    const val UNPAIRED = "This phone is not paired with this computer. Pairing is approved in the Cursor desktop app."
    const val REVOKED = "Pairing was revoked on the computer. Request it again to show a new code on the desktop."
    const val REJECTED = "The desktop declined this phone. Request pairing again if that was a mistake."
    const val INBOX = "Local agents"
    const val NO_AGENTS = "No local agents on this computer right now."
    const val REPLY = "Reply"
    const val START = "Start task"
    const val START_HINT = "Starts a local agent on this computer. It is not a cloud agent."
    const val WORKSPACE = "Workspace"
    const val MESSAGE = "Message"
    const val NEEDS_MODE_TITLE = "Needs Extended mode"
    const val NEEDS_MODE_BODY =
        "The Oct 6 iOS product — a list of computers, approve pairing on the desktop, then check in / reply / start on local agents that stay on the computer — uses Cursor's private Remote Control API. It is not in the documented Cloud Agents API, so this app only calls it in Extended mode."
    const val MACHINES_HINT =
        "Cloud agents can still run tools on a connected machine from New Chat's device picker. That is My Machines, not this pairing, and the agent loop stays in Cursor's cloud."
    const val CODE_LABEL = "Verification code"
    const val TRANSCRIPT = "On this computer"
}

object ComputersTags {
    const val SETTINGS_ROW = "settings_computers"
    const val PAGE = "computers_page"
    const val DETAIL = "computer_page"
    const val PAIR_BUTTON = "computer_pair"
    const val CODE = "computer_verification_code"
    const val REPLY_FIELD = "computer_reply_field"
    const val REPLY_SEND = "computer_reply_send"
    const val START_FIELD = "computer_start_field"
    const val START_SEND = "computer_start_send"
    fun computerRow(targetId: String): String = "computer_row_$targetId"
    fun sessionRow(sessionId: String): String = "computer_session_$sessionId"
    const val TRANSCRIPT = "computer_transcript"
}
