package com.GalleryYou.Pixel

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAlbumScreen(
    settings: AppSettings,
    onClose: () -> Unit,
    onOpen: (List<MediaFile>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MediaFile>?>(null) }
    var selected by remember { mutableStateOf<Set<android.net.Uri>>(emptySet()) }
    var loadingError by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() as? LifecycleOwner }
    val window = remember(context) { context.findActivity()?.window }

    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { HiddenVault.unlock(context) }
        } catch (e: IOException) {
            loadingError = true
        } catch (e: GeneralSecurityException) {
            loadingError = true
        } catch (e: SecurityException) {
            loadingError = true
        }
    }

    DisposableEffect(activity, window) {
        val originalSecureFlag =
            ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onClose()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            if (window != null && !originalSecureFlag) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            HiddenVault.clearDecryptedCache(context)
        }
    }

    BackHandler { onClose() }

    val media = items.orEmpty()
    val picked = media.filter { it.uri in selected }

    fun restoreSelected() {
        if (picked.isEmpty()) return
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { HiddenVault.restore(context, picked) }
                selected = emptySet()
                items = withContext(Dispatchers.IO) { HiddenVault.unlock(context) }
                Toast.makeText(
                    context,
                    if (restored > 0) context.getString(R.string.toast_restored)
                    else context.getString(R.string.hidden_restore_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: IOException) {
                Toast.makeText(context, context.getString(R.string.hidden_restore_failed), Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(context, context.getString(R.string.hidden_restore_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (selected.isNotEmpty()) {
                SelectionTopBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = media.map { it.uri }.toSet() },
                ) {
                    IconButton(onClick = ::restoreSelected) {
                        Icon(AppIcons.TrashRestore, contentDescription = stringResource(R.string.hidden_restore))
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.hidden_album)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            items == null && !loadingError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadingError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.hidden_open_failed), color = MaterialTheme.colorScheme.error)
            }
            media.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.hidden_album_empty))
            }
            else -> MediaGrid(
                items = media,
                padding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                settings = settings,
                selected = selected,
                header = null,
                onClick = { item, index ->
                    if (selected.isNotEmpty()) {
                        selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                    } else {
                        onOpen(media, index)
                    }
                },
                onLongClick = { item ->
                    selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                },
            )
        }
    }
}
