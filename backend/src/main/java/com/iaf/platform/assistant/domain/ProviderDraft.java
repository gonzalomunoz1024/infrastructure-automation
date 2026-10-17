package com.iaf.platform.assistant.domain;

import java.util.List;

/** The answer of "Ask Devin" in the Add provider wizard: a provider file plus what was assumed. */
public record ProviderDraft(String yaml, String summary, List<String> assumptions, List<String> questions) {
}
