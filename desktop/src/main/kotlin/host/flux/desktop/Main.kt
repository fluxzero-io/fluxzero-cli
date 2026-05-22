package host.flux.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.CliStatus
import host.flux.desktop.model.DesktopBuildSystem
import host.flux.desktop.model.GenerateProjectRequest
import host.flux.desktop.model.GeneratedProject
import host.flux.desktop.services.AgentAvailability
import host.flux.desktop.services.AgentLauncher
import host.flux.desktop.services.AgentLaunchResult
import host.flux.desktop.services.AppPaths
import host.flux.desktop.services.CliRuntimeService
import host.flux.desktop.services.DeepLinkRouter
import host.flux.desktop.services.FluxzeroNewProjectLink
import host.flux.desktop.services.ProjectGenerator
import host.flux.desktop.services.ProjectRegistry
import host.flux.desktop.services.StartupDeepLinkResult
import host.flux.projectfiles.SdkVersionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

fun main(args: Array<String>) {
    DeepLinkRouter.registerSystemHandler()
    val startupResult = args
        .firstOrNull { it.startsWith("fluxzero://", ignoreCase = true) }
        ?.let(DeepLinkRouter::submitStartup)
        ?: StartupDeepLinkResult.NONE

    if (startupResult == StartupDeepLinkResult.HEADLESS) {
        return
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = APP_NAME,
            icon = painterResource("icons/fluxzero.png")
        ) {
            FluxzeroDesktopApp()
        }
    }
}

@Composable
fun FluxzeroDesktopApp(services: DesktopServices = remember { DesktopServices.create() }) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(0) }
    var cliStatus by remember { mutableStateOf<CliStatus?>(null) }
    var templates by remember { mutableStateOf<List<String>>(emptyList()) }
    var projects by remember { mutableStateOf<List<GeneratedProject>>(emptyList()) }
    var availability by remember { mutableStateOf(services.agentLauncher.detectAvailability()) }
    var loading by remember { mutableStateOf(true) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var deepLink by remember { mutableStateOf<FluxzeroNewProjectLink?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val status = services.cliRuntime.ensureLatestCli()
                    val loadedTemplates = services.cliRuntime.listTemplates()
                    val loadedProjects = services.registry.listProjects()
                    Triple(status, loadedTemplates, services.refreshSdkVersions(loadedProjects))
                }
            }
            result.onSuccess { (status, loadedTemplates, loadedProjects) ->
                cliStatus = status
                templates = loadedTemplates.ifEmpty { DEFAULT_TEMPLATES }
                projects = loadedProjects
                availability = services.agentLauncher.detectAvailability()
            }.onFailure {
                templates = DEFAULT_TEMPLATES
                projects = services.registry.listProjects()
                snackbarHostState.showSnackbar("Could not prepare Fluxzero CLI: ${it.message}")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    DisposableEffect(Unit) {
        val removeListener = DeepLinkRouter.addListener {
            deepLink = it
            selectedTab = 0
        }
        onDispose(removeListener)
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                FluxzeroTopBar(
                    cliStatus = cliStatus,
                    loading = loading,
                    onRefresh = { refresh() }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Generate") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Projects") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Upgrades") })
                }
                Box(Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        0 -> GenerateTab(
                            services = services,
                            cliStatus = cliStatus,
                            templates = templates,
                            availability = availability,
                            deepLink = deepLink,
                            loading = loading || busyMessage != null,
                            onBusy = { busyMessage = it },
                            onProjectsChanged = {
                                projects = services.registry.listProjects()
                                selectedTab = 1
                            },
                            onMessage = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        )
                        1 -> ProjectsTab(
                            projects = projects,
                            availability = availability,
                            agentLauncher = services.agentLauncher,
                            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                        )
                        2 -> UpgradesTab(
                            projects = projects,
                            onRefresh = {
                                scope.launch {
                                    val refreshed = withContext(Dispatchers.IO) {
                                        services.refreshSdkVersions(services.registry.listProjects())
                                    }
                                    projects = refreshed
                                    snackbarHostState.showSnackbar("Project SDK status refreshed.")
                                }
                            }
                        )
                    }
                    busyMessage?.let {
                        BusyOverlay(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun FluxzeroTopBar(cliStatus: CliStatus?, loading: Boolean, onRefresh: () -> Unit) {
    TopAppBar(
        title = {
            Text(APP_NAME, fontWeight = FontWeight.Bold)
        },
        actions = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh CLI and projects")
                }
            }
        }
    )
}

@Composable
private fun GenerateTab(
    services: DesktopServices,
    cliStatus: CliStatus?,
    templates: List<String>,
    availability: AgentAvailability,
    deepLink: FluxzeroNewProjectLink?,
    loading: Boolean,
    onBusy: (String?) -> Unit,
    onProjectsChanged: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var template by remember(templates) { mutableStateOf(preferredTemplate(templates, "flux-basic-java")) }
    var projectName by remember { mutableStateOf("") }
    var outputDir by remember { mutableStateOf(Path.of(System.getProperty("user.home"), "FluxzeroProjects").toString()) }
    var groupId by remember { mutableStateOf(DEFAULT_GROUP_ID) }
    var artifactId by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(defaultPackageName(groupId, artifactId)) }
    var description by remember { mutableStateOf("A Fluxzero application") }
    var buildSystem by remember { mutableStateOf(defaultBuildSystemForTemplate(template)) }
    var initGit by remember { mutableStateOf(true) }
    var firstPrompt by remember { mutableStateOf("") }
    var agentChoice by remember { mutableStateOf(defaultAgentChoice(availability)) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var artifactEdited by remember { mutableStateOf(false) }
    var packageEdited by remember { mutableStateOf(false) }

    LaunchedEffect(deepLink?.id) {
        val link = deepLink ?: return@LaunchedEffect
        link.name?.let {
            projectName = it
            val derivedArtifactId = artifactIdFromProjectName(it)
            if (!artifactEdited) {
                artifactId = derivedArtifactId
            }
            if (!packageEdited) {
                packageName = defaultPackageName(groupId, if (artifactEdited) artifactId else derivedArtifactId)
            }
        }
        link.prompt?.let { firstPrompt = it }
        link.template?.let {
            template = it
            buildSystem = defaultBuildSystemForTemplate(it)
            advancedExpanded = true
        }
        link.location?.let {
            outputDir = it
            advancedExpanded = true
        }
        link.agentChoice?.let { agentChoice = it }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = {
                        projectName = it
                        val derivedArtifactId = artifactIdFromProjectName(it)
                        if (!artifactEdited) {
                            artifactId = derivedArtifactId
                        }
                        if (!packageEdited) {
                            packageName = defaultPackageName(groupId, if (artifactEdited) artifactId else derivedArtifactId)
                        }
                    },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            OutlinedTextField(
                value = firstPrompt,
                onValueChange = { firstPrompt = it },
                label = { Text("Describe what you want to build") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    DisclosureArrow(advancedExpanded)
                    Spacer(Modifier.width(6.dp))
                    Text("Advanced options")
                }
                AgentChoiceSelector(agentChoice) { agentChoice = it }
            }
        }
        if (advancedExpanded) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = outputDir,
                        onValueChange = { outputDir = it },
                        label = { Text("Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { chooseDirectory(outputDir)?.let { outputDir = it.toString() } },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Browse")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    TemplateDropdown(
                        templates = templates,
                        selected = template,
                        onSelected = {
                            template = it
                            buildSystem = defaultBuildSystemForTemplate(it)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    BuildSystemSelector(
                        buildSystem = buildSystem,
                        onChange = { buildSystem = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = groupId,
                        onValueChange = {
                            groupId = it
                            if (!packageEdited) {
                                packageName = defaultPackageName(it, artifactId)
                            }
                        },
                        label = { Text("Group ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = artifactId,
                        onValueChange = {
                            artifactEdited = true
                            artifactId = artifactIdFromProjectName(it)
                            if (!packageEdited) {
                                packageName = defaultPackageName(groupId, artifactId)
                            }
                        },
                        label = { Text("Artifact ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = {
                        packageEdited = true
                        packageName = packageNameFromInput(it)
                    },
                    label = { Text("Package") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = initGit, onCheckedChange = { initGit = it })
                    Text("Initialize Git repository")
                }
            }
            item {
                Text(
                    text = buildString {
                        append(cliStatus?.let { "CLI ${it.version ?: "unknown"} • ${it.message}" } ?: "CLI is prepared when the app starts.")
                        append("\nRefresh checks for the latest CLI, templates, projects, SDK versions, and local agent availability.")
                    },
                    style = MaterialTheme.typography.caption,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        item {
            Button(
                enabled = !loading && cliStatus != null,
                onClick = {
                    val effectiveArtifactId = artifactId.ifBlank { artifactIdFromProjectName(projectName) }
                    val effectiveGroupId = groupId.ifBlank { DEFAULT_GROUP_ID }
                    val effectivePackageName = packageName.ifBlank { defaultPackageName(effectiveGroupId, effectiveArtifactId) }
                    val request = GenerateProjectRequest(
                        template = template,
                        name = projectName,
                        outputBaseDir = outputDir,
                        packageName = effectivePackageName,
                        groupId = effectiveGroupId,
                        artifactId = effectiveArtifactId,
                        description = description,
                        buildSystem = buildSystem,
                        initGit = initGit,
                        firstPrompt = firstPrompt,
                        agentChoice = agentChoice
                    )
                    scope.launch {
                        onBusy("Generating project")
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val generator = ProjectGenerator(
                                    cliExecutable = Path.of(cliStatus!!.executablePath),
                                    registry = services.registry
                                )
                                generator.generate(request, cliStatus.version)
                            }
                        }
                        onBusy(null)
                        result.onSuccess {
                            val launchResult = withContext(Dispatchers.IO) {
                                runCatching {
                                    services.agentLauncher.launchSelected(request.agentChoice, it.project, readPrompt(it.project))
                                }
                            }
                            onMessage("Created ${it.project.name}.${launchResult.messageFor(request.agentChoice)}")
                            onProjectsChanged()
                        }.onFailure {
                            onMessage(it.message ?: "Project generation failed.")
                        }
                    }
                }
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create project")
            }
        }
    }
}

@Composable
private fun ProjectsTab(
    projects: List<GeneratedProject>,
    availability: AgentAvailability,
    agentLauncher: AgentLauncher,
    onMessage: (String) -> Unit
) {
    if (projects.isEmpty()) {
        EmptyState("Generated projects will appear here.")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(projects, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                availability = availability,
                onOpenFolder = {
                    runCatching { agentLauncher.openProjectFolder(project.path) }
                        .onFailure { onMessage(it.message ?: "Could not open project folder.") }
                },
                onOpenCodex = {
                    runCatching { agentLauncher.launchCodex(project.path, readPrompt(project)) }
                        .onSuccess { onMessage(it.messageFor(AgentChoice.CODEX).trim().ifBlank { "Opened in Codex." }) }
                        .onFailure { onMessage(it.message ?: "Could not open Codex.") }
                },
                onOpenClaude = {
                    runCatching { agentLauncher.launchClaude(project.path, readPrompt(project)) }
                        .onFailure { onMessage(it.message ?: "Could not open Claude.") }
                },
                onCopyPrompt = {
                    copyToClipboard(readPrompt(project))
                    onMessage("Prompt copied.")
                }
            )
        }
    }
}

@Composable
private fun UpgradesTab(projects: List<GeneratedProject>, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Project upgrade status", style = MaterialTheme.typography.h6)
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
        }
        Text(
            "SDK and agent-file upgrade actions are prepared for a later release. V1 records generated projects and detects their current SDK version.",
            style = MaterialTheme.typography.body2
        )
        Divider()
        if (projects.isEmpty()) {
            EmptyState("No generated projects registered yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { project ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(project.name, fontWeight = FontWeight.Bold)
                            Text(project.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
                        }
                        Text(project.sdkVersion ?: "SDK unknown")
                        Spacer(Modifier.width(12.dp))
                        Button(enabled = false, onClick = {}) {
                            Text("Upgrade later")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: GeneratedProject,
    availability: AgentAvailability,
    onOpenFolder: () -> Unit,
    onOpenCodex: () -> Unit,
    onOpenClaude: () -> Unit,
    onCopyPrompt: () -> Unit
) {
    Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.h6)
                    Text(project.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
                }
                Text(project.sdkVersion ?: "SDK unknown", style = MaterialTheme.typography.body2)
            }
            Text("${project.template} • ${project.buildSystem} • ${project.packageName}", style = MaterialTheme.typography.body2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenFolder) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Folder")
                }
                OutlinedButton(enabled = availability.codexAvailable, onClick = onOpenCodex) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Codex")
                }
                OutlinedButton(onClick = onOpenClaude) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Claude")
                }
                OutlinedButton(onClick = onCopyPrompt) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Prompt")
                }
            }
        }
    }
}

@Composable
private fun TemplateDropdown(templates: List<String>, selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.ifBlank { "Select template" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            DisclosureArrow(expanded)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            templates.forEach { template ->
                DropdownMenuItem(onClick = {
                    onSelected(template)
                    expanded = false
                }) {
                    Text(template)
                }
            }
        }
    }
}

@Composable
private fun BuildSystemSelector(buildSystem: DesktopBuildSystem, onChange: (DesktopBuildSystem) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        DesktopBuildSystem.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = buildSystem == option, onClick = { onChange(option) })
                Text(option.label)
            }
        }
    }
}

@Composable
private fun AgentChoiceSelector(choice: AgentChoice, onChange: (AgentChoice) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Open in")
        Spacer(Modifier.width(8.dp))
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(choice.label)
                Spacer(Modifier.width(6.dp))
                DisclosureArrow(expanded)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AgentChoice.entries.forEach { option ->
                    DropdownMenuItem(
                        onClick = {
                            onChange(option)
                            expanded = false
                        }
                    ) {
                        Text(option.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureArrow(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f)
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.rotate(rotation)
    )
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(elevation = 8.dp) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(14.dp))
                Text(message)
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.body1)
    }
}

private fun defaultAgentChoice(availability: AgentAvailability): AgentChoice {
    return if (availability.codexAvailable) AgentChoice.CODEX else AgentChoice.NONE
}

private fun chooseDirectory(initial: String): Path? {
    val chooser = JFileChooser(initial)
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Choose project location"
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}

private fun readPrompt(project: GeneratedProject): String {
    val promptPath = project.promptPath?.let(Path::of)
    return if (promptPath != null && Files.isRegularFile(promptPath)) {
        Files.readString(promptPath)
    } else {
        "Open START_PROMPT.md and help me continue from there."
    }
}

private fun Result<AgentLaunchResult>.messageFor(choice: AgentChoice): String {
    return fold(
        onSuccess = { it.messageFor(choice) },
        onFailure = { " Could not open selected agent: ${it.message ?: "unknown error"}" }
    )
}

private fun AgentLaunchResult.messageFor(choice: AgentChoice): String {
    return when {
        openedCodex -> " Opened in Codex with your prompt."
        openedCodexDownload -> " Codex was not found, so the download page was opened."
        openedClaude -> " Opened in Claude."
        choice == AgentChoice.NONE -> ""
        else -> " No agent was opened."
    }
}

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

private fun preferredTemplate(templates: List<String>, preferred: String): String {
    return when {
        preferred in templates -> preferred
        templates.isNotEmpty() -> templates.first()
        else -> preferred
    }
}

private fun defaultBuildSystemForTemplate(template: String): DesktopBuildSystem {
    return if (template.contains("kotlin", ignoreCase = true)) {
        DesktopBuildSystem.GRADLE
    } else {
        DesktopBuildSystem.MAVEN
    }
}

private fun artifactIdFromProjectName(name: String): String {
    return name
        .lowercase()
        .replace(Regex("[^a-z0-9\\s_-]"), "")
        .replace(Regex("[\\s_]+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
}

private fun defaultPackageName(groupId: String, artifactId: String): String {
    val packageSuffix = artifactId
        .ifBlank { "app" }
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .ifBlank { "app" }
    return packageNameFromInput("${groupId.ifBlank { DEFAULT_GROUP_ID }}.$packageSuffix")
}

private fun packageNameFromInput(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[^a-z0-9.]"), "")
        .replace(Regex("\\.+"), ".")
        .trim('.')
}

private const val DEFAULT_GROUP_ID = "com.example"
private const val APP_NAME = "Fluxzero Launchpad"

private val DEFAULT_TEMPLATES = listOf(
    "flux-basic-java",
    "flux-basic-kotlin",
    "gamerental"
)

class DesktopServices(
    val cliRuntime: CliRuntimeService,
    val registry: ProjectRegistry,
    val agentLauncher: AgentLauncher
) {
    fun refreshSdkVersions(projects: List<GeneratedProject>): List<GeneratedProject> {
        var updated = projects
        projects.forEach { project ->
            val projectDir = Path.of(project.path)
            val sdkVersion = if (Files.isDirectory(projectDir)) {
                SdkVersionDetector.detect(projectDir)
            } else {
                null
            }
            if (sdkVersion != project.sdkVersion) {
                updated = registry.refreshSdkVersion(project.id, sdkVersion)
            }
        }
        return updated
    }

    companion object {
        fun create(): DesktopServices {
            val paths = AppPaths.detect()
            return DesktopServices(
                cliRuntime = CliRuntimeService(paths),
                registry = ProjectRegistry(paths.registryFile),
                agentLauncher = AgentLauncher()
            )
        }
    }
}
