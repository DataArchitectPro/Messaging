package com.android.messaging.ui.conversation.composer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.domain.conversation.usecase.draft.model.ConversationDraftSendProtocol
import com.android.messaging.ui.conversation.audio.model.ConversationAudioRecordingUiState
import com.android.messaging.ui.conversation.composer.model.ComposerAttachmentUiModel
import com.android.messaging.ui.conversation.composer.model.ConversationSegmentCounterUiState
import com.android.messaging.ui.conversation.preview.previewComposerWithAttachments
import com.android.messaging.ui.core.MessagingPreviewTheme
import kotlinx.collections.immutable.ImmutableList

@Suppress("UnusedParameter", "LongParameterList")
@Composable
internal fun ConversationComposerSection(
    modifier: Modifier = Modifier,
    audioRecording: ConversationAudioRecordingUiState,
    attachments: ImmutableList<ComposerAttachmentUiModel>,
    messageText: String,
    messageTextRevision: Int,
    subjectText: String,
    sendProtocol: ConversationDraftSendProtocol,
    segmentCounter: ConversationSegmentCounterUiState?,
    isMessageFieldEnabled: Boolean,
    isAttachmentActionEnabled: Boolean,
    isRecordActionEnabled: Boolean,
    isSendActionEnabled: Boolean,
    shouldShowRecordAction: Boolean,
    messageFieldFocusRequester: FocusRequester,
    onContactAttachClick: () -> Unit,
    onMediaPickerClick: () -> Unit,
    onMessageTextChange: (text: String, textRevision: Int) -> Unit,
    onPendingAttachmentRemove: (String) -> Unit,
    onResolvedAttachmentClick: (ComposerAttachmentUiModel.Resolved) -> Unit,
    onResolvedAttachmentRemove: (String) -> Unit,
    onAudioRecordingStartRequest: () -> Unit,
    onLockedAudioRecordingStartRequest: () -> Unit,
    onAudioRecordingFinish: () -> Unit,
    onAudioRecordingLock: () -> Boolean,
    onAudioRecordingCancel: () -> Unit,
    onSendClick: () -> Unit,
    onSendActionLongClick: () -> Unit,
    onSubjectChipClick: () -> Unit,
    onSubjectChipClear: () -> Unit,
) {
    // receive-only: no attachments, no compose bar, nothing that can send
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(16.dp),
            text = stringResource(R.string.receive_only_composer_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationComposerSectionPreview() {
    val uiState = previewComposerWithAttachments()
    MessagingPreviewTheme {
        ConversationComposerSection(
            audioRecording = uiState.audioRecording,
            attachments = uiState.attachments,
            messageText = uiState.messageText,
            messageTextRevision = uiState.messageTextRevision,
            subjectText = uiState.subjectText,
            sendProtocol = uiState.sendProtocol,
            segmentCounter = uiState.segmentCounter,
            isMessageFieldEnabled = uiState.isMessageFieldEnabled,
            isAttachmentActionEnabled = uiState.isAttachmentActionEnabled,
            isRecordActionEnabled = uiState.isRecordActionEnabled,
            isSendActionEnabled = uiState.isSendEnabled,
            shouldShowRecordAction = uiState.shouldShowRecordAction,
            messageFieldFocusRequester = FocusRequester(),
            onContactAttachClick = {},
            onMediaPickerClick = {},
            onMessageTextChange = { _, _ -> },
            onPendingAttachmentRemove = { _ -> },
            onResolvedAttachmentClick = { _ -> },
            onResolvedAttachmentRemove = { _ -> },
            onAudioRecordingStartRequest = {},
            onLockedAudioRecordingStartRequest = {},
            onAudioRecordingFinish = {},
            onAudioRecordingLock = { true },
            onAudioRecordingCancel = {},
            onSendClick = {},
            onSendActionLongClick = {},
            onSubjectChipClick = {},
            onSubjectChipClear = {},
        )
    }
}
