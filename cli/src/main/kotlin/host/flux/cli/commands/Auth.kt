package host.flux.cli.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.fluxzero.tools.account.AccountStatus
import io.fluxzero.tools.account.AccountException
import io.fluxzero.tools.account.LocalAccountService

class Auth : CliktCommand(name = "auth") {
    init { subcommands(AuthLogin(), AuthStatus(), AuthLogout()) }
    override fun help(context: Context) = "Sign in to optional Fluxzero account features"
    override fun run() = Unit
}

private class AuthLogin : CliktCommand(name = "login") {
    private val another by option("--use-another-account", help = "Request a fresh sign-in instead of reusing the browser session.").flag()
    private val json by option("--json", help = "Print account status as JSON. Credentials are never printed.").flag()
    override fun help(context: Context) = "Sign in using your browser; local development does not require an account"
    override fun run() {
        accountAction(json) { account ->
            if (!json) echo("Opening your browser to sign in to Fluxzero…")
            account.startLogin(another)
            val status = account.awaitLogin()
            printStatus(status, json)
            if (status.state() != "signed_in" || status.message() != null) throw ProgramResult(1)
        }
    }
}

private class AuthStatus : CliktCommand(name = "status") {
    private val check by option("--check", help = "Explicitly verify the remembered session with Fluxzero.").flag()
    private val json by option("--json", help = "Print non-secret account status as JSON.").flag()
    private val refresh by option("--refresh", help = "Explicitly renew an expired sign-in using secure storage.").flag()
    override fun help(context: Context) = "Read local account status without contacting Cloud or opening credential dialogs"
    override fun run() {
        accountAction(json) { account ->
            printStatus(if (check) account.checkSession(true) else if (refresh) account.refresh() else account.status(), json)
        }
    }
}

private class AuthLogout : CliktCommand(name = "logout") {
    private val json by option("--json", help = "Print non-secret account status as JSON.").flag()
    override fun help(context: Context) = "Sign out of CLI and Devboards on this computer"
    override fun run() { accountAction(json) { printStatus(it.logout(), json) } }
}

private fun CliktCommand.accountAction(json: Boolean, action: (LocalAccountService) -> Unit) {
    try { LocalAccountService.system().use(action) }
    catch (error: AccountException) { accountError(error.code(), error.message ?: "Account features are unavailable.", json) }
    catch (error: IllegalArgumentException) { accountError("configuration_unavailable", "Your account connection is not configured correctly. Check your account settings.", json) }
}

private fun CliktCommand.accountError(code: String, message: String, json: Boolean): Nothing {
    if (json) echo(ObjectMapper().writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))))
    else echo(message, err = true)
    throw ProgramResult(1)
}

private fun CliktCommand.printStatus(status: AccountStatus, json: Boolean) {
    echo(if (json) ObjectMapper().writeValueAsString(status) else status.message() ?: when (status.state()) {
        "signed_in" -> "Signed in as ${status.displayName() ?: "Fluxzero account"}."
        "signing_in" -> "Finish signing in in your browser."
        "sign_in_required" -> "Sign in again when you need Cloud access. Local development is available."
        "unavailable" -> "Account information is unavailable. You can keep working locally."
        else -> "Signed out. Local development is available."
    })
}
