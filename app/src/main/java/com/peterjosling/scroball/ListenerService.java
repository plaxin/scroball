package com.peterjosling.scroball;

import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.service.notification.NotificationListenerService;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class ListenerService extends NotificationListenerService
    implements MediaSessionManager.OnActiveSessionsChangedListener {

  private static final String TAG = ListenerService.class.getName();

  private List<MediaController> mediaControllers = new ArrayList<>();
  private Map<MediaController, MediaController.Callback> controllerCallbacks = new WeakHashMap<>();
  private PlaybackTracker playbackTracker;
  private SharedPreferences sharedPreferences;

  @Override
  public void onCreate() {
    ScroballApplication application = (ScroballApplication) getApplication();
    sharedPreferences = application.getSharedPreferences();

    ConnectivityManager connectivityManager =
        (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);

    ScroballDB scroballDB = application.getScroballDB();

    ScrobbleNotificationManager scrobbleNotificationManager =
        new ScrobbleNotificationManager(this, sharedPreferences);

    LastfmClient lastfmClient = application.getLastfmClient();

    Scrobbler scrobbler = new Scrobbler(
        lastfmClient,
        scrobbleNotificationManager,
        scroballDB, connectivityManager);

    playbackTracker = new PlaybackTracker(
        scrobbleNotificationManager,
        scroballDB,
        connectivityManager,
        scrobbler);

    Log.i(TAG, "NotificationListenerService started");

    MediaSessionManager mediaSessionManager = (MediaSessionManager) getApplicationContext()
        .getSystemService(Context.MEDIA_SESSION_SERVICE);

    ComponentName componentName = new ComponentName(this, this.getClass());
    mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName);

    NetworkStateReceiver networkStateReceiver = new NetworkStateReceiver(scrobbler);
    IntentFilter filter = new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
    this.registerReceiver(networkStateReceiver, filter);

    // Trigger change event with existing set of sessions.
    List<MediaController> initialSessions = mediaSessionManager.getActiveSessions(componentName);
    onActiveSessionsChanged(initialSessions);

  }

  public static boolean isNotificationAccessEnabled(Context context) {
    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.getPackageName());
  }

  @Override
  public void onActiveSessionsChanged(List<MediaController> activeMediaControllers) {
    Set<MediaController> existingControllers = new HashSet<>(mediaControllers);
    Log.i(TAG, "Active MediaSessions changed");

    Set<MediaController> newControllers = new HashSet<>(activeMediaControllers);

    Set<MediaController> toRemove = Sets.difference(existingControllers, newControllers);
    Set<MediaController> toAdd = Sets.difference(newControllers, existingControllers);

    for (MediaController controller : toRemove) {
      if (controllerCallbacks.containsKey(controller)) {
        controller.unregisterCallback(controllerCallbacks.get(controller));
        playbackTracker.handleSessionTermination(controller.getPackageName());
      }
    }

    for (final MediaController controller : toAdd) {
      String packageName = controller.getPackageName();
      String prefKey = "player." + packageName;

      if (!sharedPreferences.contains(prefKey)) {
        boolean defaultVal = sharedPreferences.getBoolean("scrobble_new_players", true);

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(prefKey, defaultVal);
        editor.apply();
      }

      if (!sharedPreferences.getBoolean(prefKey, true)) {
        Log.i(TAG, String.format("Ignoring player %s", packageName));
        continue;
      }

      Log.i(TAG, String.format("Listening for events from %s", packageName));

      MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
          controllerPlaybackStateChanged(controller, state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
          controllerMetadataChanged(controller, metadata);
        }
      };

      controllerCallbacks.put(controller, callback);
      controller.registerCallback(callback);

      // Media may already be playing - update with current state.
      controllerPlaybackStateChanged(controller, controller.getPlaybackState());
      controllerMetadataChanged(controller, controller.getMetadata());
    }

    mediaControllers = activeMediaControllers;
  }

  private void controllerPlaybackStateChanged(MediaController controller, PlaybackState state) {
    playbackTracker.handlePlaybackStateChange(controller.getPackageName(), state);
  }

  private void controllerMetadataChanged(MediaController controller, MediaMetadata metadata) {
    playbackTracker.handleMetadataChange(controller.getPackageName(), metadata);
  }
}
