package app.atlas.ui.go

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What a headset's buttons ask of the guide: play/pause repeats, next reads ahead, previous gives the summary. */
enum class HeadsetCommand { REPEAT, NEXT, SUMMARY }

/**
 * A player with nothing to play, only so the headset's buttons reach the guide: whatever the play/pause button sends
 * becomes [HeadsetCommand.REPEAT] (and the state stays "playing", so the next press is a press again), next and
 * previous become [HeadsetCommand.NEXT] and [HeadsetCommand.SUMMARY].
 */
@OptIn(UnstableApi::class)
class GuidancePlayer(looper: Looper, private val title: String, private val onCommand: (HeadsetCommand) -> Unit) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_STOP,
            ).build(),
        )
        .setPlaylist(listOf(MediaItemData.Builder("atlas-guidance").setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()))
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onCommand(HeadsetCommand.REPEAT)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onCommand(HeadsetCommand.NEXT)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onCommand(HeadsetCommand.SUMMARY)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/**
 * The media session that exists only while a trip is guided, so a headset press repeats the last instruction; [release]
 * when the trip ends, so the buttons go back to the music.
 */
@OptIn(UnstableApi::class)
class HeadsetControl(context: Context, title: String, onCommand: (HeadsetCommand) -> Unit) {
    private val player = GuidancePlayer(Looper.getMainLooper(), title, onCommand)
    private val session: MediaSession = MediaSession.Builder(context, player).setId("atlas-guidance").build()

    val isReleased: Boolean get() = released
    private var released = false

    fun release() {
        if (released) return
        released = true
        session.release()
        player.release()
    }
}
