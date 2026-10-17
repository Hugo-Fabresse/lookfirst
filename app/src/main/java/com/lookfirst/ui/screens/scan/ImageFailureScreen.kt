package com.lookfirst.ui.screens.scan

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lookfirst.R
import com.lookfirst.data.scan.image.ImageFailure

private val MinTarget = 48.dp

@StringRes
internal fun ImageFailure.message(): Int = when (this) {
    ImageFailure.NO_QR -> R.string.err_no_qr
    ImageFailure.UNREADABLE -> R.string.err_unreadable
    ImageFailure.TOO_LARGE -> R.string.err_image_too_big
    ImageFailure.CORRUPT -> R.string.err_image_corrupt
}

/**
 * The picked image gave nothing (ux.md 5.4): why, then "Choose another image" (filled) and "Back to camera".
 * No preview of the image is drawn: it is not kept after the read. System back is "Back to camera".
 */
@Composable
fun ImageFailureScreen(
    reason: ImageFailure,
    onPickAnother: () -> Unit,
    onBackToCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBackToCamera)
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackToCamera) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back_to_camera),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.image_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(reason.message()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onPickAnother, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) {
                Text(stringResource(R.string.pick_other_image))
            }
            OutlinedButton(onClick = onBackToCamera, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) {
                Text(stringResource(R.string.back_to_camera))
            }
        }
    }
}
