const consoleConfig = window.RELEASE_CONSOLE;
const state = { status: null, channel: null, publishing: false };

const channelCopy = {
  alpha: "Early crew build. Features may still change.",
  beta: "Release candidate. Only blocking fixes should follow.",
  stable: "Public stable build from main.",
};

const elements = {
  readyBadge: document.querySelector("#readyBadge"),
  branch: document.querySelector("#branchValue"),
  commit: document.querySelector("#commitValue"),
  worktree: document.querySelector("#worktreeValue"),
  sync: document.querySelector("#syncValue"),
  upstream: document.querySelector("#upstreamValue"),
  signing: document.querySelector("#signingValue"),
  readinessHelp: document.querySelector("#readinessHelp"),
  channels: document.querySelector("#channelButtons"),
  tag: document.querySelector("#tagInput"),
  tagHelp: document.querySelector("#tagHelp"),
  publication: document.querySelector("#publicationValue"),
  publish: document.querySelector("#publishButton"),
  refresh: document.querySelector("#refreshButton"),
  suggest: document.querySelector("#suggestButton"),
  result: document.querySelector("#resultBox"),
  actions: document.querySelector("#actionsLink"),
  releases: document.querySelector("#releasesLink"),
};

function validTagForChannel(tag, channel) {
  if (channel === "stable") return /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(tag);
  return new RegExp(`^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-${channel}\\.([1-9][0-9]*)$`).test(tag);
}

function useSuggestion() {
  if (!state.status || !state.channel) return;
  elements.tag.value = state.status.suggestions[state.channel] || "";
  updatePublishState();
}

function selectChannel(channel) {
  state.channel = channel;
  document.querySelectorAll(".channel-button").forEach((button) => {
    const selected = button.dataset.channel === channel;
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-checked", selected ? "true" : "false");
  });
  elements.publication.textContent = channel === "stable" ? "Stable GitHub Release" : `${channel[0].toUpperCase()}${channel.slice(1)} pre-release`;
  useSuggestion();
}

function renderChannels() {
  elements.channels.replaceChildren();
  const allowed = state.status.allowedChannels;
  ["alpha", "beta", "stable"].forEach((channel) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "channel-button";
    button.dataset.channel = channel;
    button.setAttribute("role", "radio");
    button.setAttribute("aria-checked", "false");
    button.disabled = !allowed.includes(channel);
    const title = document.createElement("strong");
    title.textContent = channel;
    const description = document.createElement("span");
    description.textContent = allowed.includes(channel) ? channelCopy[channel] : "Not permitted from this branch.";
    button.append(title, description);
    button.addEventListener("click", () => selectChannel(channel));
    elements.channels.append(button);
  });
  if (!allowed.includes(state.channel)) state.channel = allowed[0] || null;
  if (state.channel) selectChannel(state.channel);
  else updatePublishState();
}

function updatePublishState() {
  const tag = elements.tag.value.trim();
  const valid = Boolean(state.channel && validTagForChannel(tag, state.channel));
  elements.tagHelp.textContent = valid
    ? "Version format is valid. The online workflow derives Android version values automatically."
    : state.channel
      ? `Use the ${state.channel} tag format shown by the suggestion.`
      : "This branch cannot publish a release.";
  elements.tagHelp.classList.toggle("invalid", Boolean(tag) && !valid);
  elements.publish.disabled = !(state.status?.ready && valid) || state.publishing;
  elements.publish.textContent = state.publishing
    ? "Publishing tag…"
    : state.channel
      ? `Publish ${state.channel} online`
      : "Release unavailable";
}

function renderStatus() {
  const status = state.status;
  elements.branch.textContent = status.branch;
  elements.commit.textContent = `Commit ${status.commit}`;
  elements.worktree.textContent = status.clean ? "Clean" : `${status.changedCount} uncommitted change${status.changedCount === 1 ? "" : "s"}`;
  elements.sync.textContent = status.synchronized ? "Up to date" : "Needs attention";
  elements.upstream.textContent = status.synchronized
    ? status.upstream
    : `${status.upstream} · ahead ${status.ahead ?? "?"} / behind ${status.behind ?? "?"}`;
  elements.signing.textContent = status.signingNote;
  elements.readyBadge.textContent = status.ready ? "Ready" : "Not ready";
  elements.readyBadge.className = `badge ${status.ready ? "ready" : "blocked"}`;
  if (!status.clean) elements.readinessHelp.textContent = "Commit the current changes before publishing.";
  else if (!status.synchronized) elements.readinessHelp.textContent = "Push or reconcile this branch with GitHub first.";
  else if (!status.allowedChannels.length) elements.readinessHelp.textContent = "Switch to develop, release, or main to publish.";
  else elements.readinessHelp.textContent = "All local safety checks pass. GitHub will run the full quality gate next.";
  elements.actions.href = status.actionsUrl;
  elements.releases.href = status.releasesUrl;
  renderChannels();
}

async function refreshStatus() {
  elements.refresh.disabled = true;
  elements.readyBadge.textContent = "Checking…";
  elements.readyBadge.className = "badge neutral";
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok || !payload.ok) throw new Error(payload.error || "Status check failed.");
    state.status = payload.status;
    renderStatus();
  } catch (error) {
    elements.readyBadge.textContent = "Unavailable";
    elements.readyBadge.className = "badge blocked";
    elements.readinessHelp.textContent = error.message;
  } finally {
    elements.refresh.disabled = false;
  }
}

async function publishRelease() {
  const tag = elements.tag.value.trim();
  if (!state.status?.ready || !state.channel || !validTagForChannel(tag, state.channel)) return;
  const confirmed = window.confirm(`Create and push the immutable tag ${tag}?\n\nGitHub will begin the signed ${state.channel} release automatically.`);
  if (!confirmed) return;
  state.publishing = true;
  elements.result.hidden = false;
  elements.result.className = "result-box";
  elements.result.textContent = "Checking the remote branch and publishing the tag…";
  updatePublishState();
  try {
    const response = await fetch("/api/publish", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Release-Console-Token": consoleConfig.token,
      },
      body: JSON.stringify({ tag }),
    });
    const payload = await response.json();
    if (!response.ok || !payload.ok) throw new Error(payload.error || "Release publication failed.");
    elements.result.textContent = `${payload.message}\n\nThe online quality gate is now running. Use “Open Release Actions” to follow it.`;
    await refreshStatus();
  } catch (error) {
    elements.result.className = "result-box error";
    elements.result.textContent = error.message;
  } finally {
    state.publishing = false;
    updatePublishState();
  }
}

elements.refresh.addEventListener("click", refreshStatus);
elements.suggest.addEventListener("click", useSuggestion);
elements.tag.addEventListener("input", updatePublishState);
elements.publish.addEventListener("click", publishRelease);
refreshStatus();
