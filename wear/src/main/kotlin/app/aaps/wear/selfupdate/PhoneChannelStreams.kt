package app.aaps.wear.selfupdate

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.tasks.await
import java.io.InputStream
import java.io.OutputStream

/** The streams of a channel that the phone app opened to this watch app; a fake in tests */
interface PhoneChannelStreams {

    suspend fun input(channel: ChannelClient.Channel): InputStream
    suspend fun output(channel: ChannelClient.Channel): OutputStream
    fun close(channel: ChannelClient.Channel)
}

/** [PhoneChannelStreams] of the Wear OS `ChannelClient` */
@ContributesBinding(AppScope::class)
@Inject
class WearablePhoneChannelStreams(private val context: Context) : PhoneChannelStreams {

    private val client get() = Wearable.getChannelClient(context)

    override suspend fun input(channel: ChannelClient.Channel): InputStream = client.getInputStream(channel).await()

    override suspend fun output(channel: ChannelClient.Channel): OutputStream = client.getOutputStream(channel).await()

    override fun close(channel: ChannelClient.Channel) {
        client.close(channel)
    }
}
