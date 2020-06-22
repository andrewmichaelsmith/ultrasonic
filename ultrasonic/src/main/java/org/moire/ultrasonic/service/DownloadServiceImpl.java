/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.koin.java.standalone.KoinJavaComponent;
import org.moire.ultrasonic.audiofx.EqualizerController;
import org.moire.ultrasonic.audiofx.VisualizerController;
import org.moire.ultrasonic.domain.MusicDirectory;
import org.moire.ultrasonic.domain.MusicDirectory.Entry;
import org.moire.ultrasonic.domain.PlayerState;
import org.moire.ultrasonic.domain.RepeatMode;
import org.moire.ultrasonic.domain.UserInfo;
import org.moire.ultrasonic.featureflags.Feature;
import org.moire.ultrasonic.featureflags.FeatureStorage;
import org.moire.ultrasonic.util.LRUCache;
import org.moire.ultrasonic.util.ShufflePlayBuffer;
import org.moire.ultrasonic.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import kotlin.Lazy;

import static org.koin.java.standalone.KoinJavaComponent.inject;
import static org.moire.ultrasonic.service.MediaPlayerService.playerState;

/**
 * @author Sindre Mehus, Joshua Bahnsen
 * @version $Id$
 */
public class DownloadServiceImpl implements DownloadService
{
	private static final String TAG = DownloadServiceImpl.class.getSimpleName();

	private final LRUCache<MusicDirectory.Entry, DownloadFile> downloadFileCache = new LRUCache<>(100);

	private String suggestedPlaylistName;
	private boolean keepScreenOn;

	private boolean showVisualization;
	private boolean autoPlayStart;

	private Context context;
	public Lazy<JukeboxService> jukeboxService = inject(JukeboxService.class);
	private Lazy<DownloadQueueSerializer> downloadQueueSerializer = inject(DownloadQueueSerializer.class);
	private Lazy<ExternalStorageMonitor> externalStorageMonitor = inject(ExternalStorageMonitor.class);

	public DownloadServiceImpl(Context context)
	{
		this.context = context;

		// TODO: refactor
		MediaPlayerService.shufflePlayBuffer = new ShufflePlayBuffer(context);
		externalStorageMonitor.getValue().onCreate(new Runnable() {
			@Override
			public void run() {
				reset();
			}
		});

		int instance = Util.getActiveServer(context);
		setJukeboxEnabled(Util.getJukeboxEnabled(context, instance));

		Log.i(TAG, "DownloadServiceImpl created");
	}

	public void onDestroy()
	{
		externalStorageMonitor.getValue().onDestroy();
		context.stopService(new Intent(context, MediaPlayerService.class));
		Log.i(TAG, "DownloadServiceImpl destroyed");
	}

	private void executeOnStartedMediaPlayerService(final Consumer<MediaPlayerService> taskToExecute)
	{
		Thread t = new Thread()
		{
			public void run()
			{
				MediaPlayerService instance = MediaPlayerService.getInstance(context);
				taskToExecute.accept(instance);
			}
		};
		t.start();
	}

	@Override
	public void restore(List<MusicDirectory.Entry> songs, final int currentPlayingIndex, final int currentPlayingPosition, final boolean autoPlay, boolean newPlaylist)
	{
		download(songs, false, false, false, false, newPlaylist);

		if (currentPlayingIndex != -1)
		{
			executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
				@Override
				public void accept(MediaPlayerService mediaPlayerService) {
					mediaPlayerService.play(currentPlayingIndex, autoPlayStart);
				}
			});

			if (MediaPlayerService.currentPlaying != null)
			{
				if (autoPlay && jukeboxService.getValue().isEnabled())
				{
					jukeboxService.getValue().skip(getCurrentPlayingIndex(), currentPlayingPosition / 1000);
				}
				else
				{
					if (MediaPlayerService.currentPlaying.isCompleteFileAvailable())
					{
						executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
							@Override
							public void accept(MediaPlayerService mediaPlayerService) {
								mediaPlayerService.doPlay(MediaPlayerService.currentPlaying, currentPlayingPosition, autoPlay);
							}
						});
					}
				}
			}

			autoPlayStart = false;
		}
	}

	@Override
	public synchronized void play(final int index)
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.play(index, true);
			}
		});
	}

	public synchronized void play()
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.play();
			}
		});
	}

	@Override
	public synchronized void togglePlayPause()
	{
		if (playerState == PlayerState.IDLE) autoPlayStart = true;
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.togglePlayPause();
			}
		});
	}

	@Override
	public synchronized void seekTo(final int position)
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.seekTo(position);
			}
		});
	}

	@Override
	public synchronized void pause()
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.pause();
			}
		});
	}

	@Override
	public synchronized void start()
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.start();
			}
		});
	}

	@Override
	public synchronized void stop()
	{
		executeOnStartedMediaPlayerService(new Consumer<MediaPlayerService>() {
			@Override
			public void accept(MediaPlayerService mediaPlayerService) {
				mediaPlayerService.stop();
			}
		});
	}

	@Override
	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext, boolean shuffle, boolean newPlaylist)
	{
		MediaPlayerService.shufflePlay = false;
		int offset = 1;

		if (songs.isEmpty())
		{
			return;
		}

		if (newPlaylist)
		{
			MediaPlayerService.downloadList.clear();
		}

		if (playNext)
		{
			if (autoplay && getCurrentPlayingIndex() >= 0)
			{
				offset = 0;
			}

			for (MusicDirectory.Entry song : songs)
			{
				DownloadFile downloadFile = new DownloadFile(context, song, save);
				MediaPlayerService.downloadList.add(getCurrentPlayingIndex() + offset, downloadFile);
				offset++;
			}
		}
		else
		{
			int size = size();
			int index = getCurrentPlayingIndex();

			for (MusicDirectory.Entry song : songs)
			{
				DownloadFile downloadFile = new DownloadFile(context, song, save);
				MediaPlayerService.downloadList.add(downloadFile);
			}

			if (!autoplay && (size - 1) == index)
			{
				MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
				if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
			}

		}
		MediaPlayerService.revision++;

		jukeboxService.getValue().updatePlaylist();

		if (shuffle) shuffle();

		if (autoplay)
		{
			play(0);
		}
		else
		{
			if (MediaPlayerService.currentPlaying == null)
			{
				MediaPlayerService.currentPlaying = MediaPlayerService.downloadList.get(0);
				MediaPlayerService.currentPlaying.setPlaying(true);
			}

			MediaPlayerService.checkDownloads(context);
		}

		downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
	}

	@Override
	public synchronized void downloadBackground(List<MusicDirectory.Entry> songs, boolean save)
	{
		for (MusicDirectory.Entry song : songs)
		{
			DownloadFile downloadFile = new DownloadFile(context, song, save);
			MediaPlayerService.backgroundDownloadList.add(downloadFile);
		}

		MediaPlayerService.revision++;

		MediaPlayerService.checkDownloads(context);
		downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
	}

	public synchronized void setCurrentPlaying(DownloadFile currentPlaying)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setCurrentPlaying(currentPlaying);
	}

	public synchronized void setCurrentPlaying(int index)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setCurrentPlaying(index);
	}

	public synchronized void setPlayerState(PlayerState state)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setPlayerState(state);
	}

	@Override
	public void stopJukeboxService()
	{
		jukeboxService.getValue().stopJukeboxService();
	}

	@Override
	public void startJukeboxService()
	{
		jukeboxService.getValue().startJukeboxService();
	}

	@Override
	public synchronized void setShufflePlayEnabled(boolean enabled)
	{
		MediaPlayerService.shufflePlay = enabled;
		if (MediaPlayerService.shufflePlay)
		{
			clear();
			MediaPlayerService.checkDownloads(context);
		}
	}

	@Override
	public boolean isShufflePlayEnabled()
	{
		return MediaPlayerService.shufflePlay;
	}

	@Override
	public synchronized void shuffle()
	{
		Collections.shuffle(MediaPlayerService.downloadList);
		if (MediaPlayerService.currentPlaying != null)
		{
			MediaPlayerService.downloadList.remove(getCurrentPlayingIndex());
			MediaPlayerService.downloadList.add(0, MediaPlayerService.currentPlaying);
		}
		MediaPlayerService.revision++;
		downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxService.getValue().updatePlaylist();

		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
	}

	@Override
	public RepeatMode getRepeatMode()
	{
		return Util.getRepeatMode(context);
	}

	@Override
	public void setRepeatMode(RepeatMode repeatMode)
	{
		Util.setRepeatMode(context, repeatMode);
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
	}

	@Override
	public boolean getKeepScreenOn()
	{
		return keepScreenOn;
	}

	@Override
	public void setKeepScreenOn(boolean keepScreenOn)
	{
		this.keepScreenOn = keepScreenOn;
	}

	@Override
	public boolean getShowVisualization()
	{
		return showVisualization;
	}

	@Override
	public void setShowVisualization(boolean showVisualization)
	{
		this.showVisualization = showVisualization;
	}

	@Override
	public synchronized DownloadFile forSong(MusicDirectory.Entry song)
	{
		for (DownloadFile downloadFile : MediaPlayerService.downloadList)
		{
			if (downloadFile.getSong().equals(song) && ((downloadFile.isDownloading() && !downloadFile.isDownloadCancelled() && downloadFile.getPartialFile().exists()) || downloadFile.isWorkDone()))
			{
				return downloadFile;
			}
		}
		for (DownloadFile downloadFile : MediaPlayerService.backgroundDownloadList)
		{
			if (downloadFile.getSong().equals(song))
			{
				return downloadFile;
			}
		}

		DownloadFile downloadFile = downloadFileCache.get(song);
		if (downloadFile == null)
		{
			downloadFile = new DownloadFile(context, song, false);
			downloadFileCache.put(song, downloadFile);
		}
		return downloadFile;
	}

	@Override
	public synchronized void clear()
	{
		clear(true);
	}

	public synchronized void clear(boolean serialize)
	{
		MediaPlayerService.clear(serialize);
		jukeboxService.getValue().updatePlaylist();
		if (serialize)
		{
			downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
		}
	}

	@Override
	public synchronized void clearBackground()
	{
		if (MediaPlayerService.currentDownloading != null && MediaPlayerService.backgroundDownloadList.contains(MediaPlayerService.currentDownloading))
		{
			MediaPlayerService.currentDownloading.cancelDownload();
			MediaPlayerService.currentDownloading = null;
		}
		MediaPlayerService.backgroundDownloadList.clear();
	}

	@Override
	public synchronized void clearIncomplete()
	{
		reset();
		Iterator<DownloadFile> iterator = MediaPlayerService.downloadList.iterator();

		while (iterator.hasNext())
		{
			DownloadFile downloadFile = iterator.next();
			if (!downloadFile.isCompleteFileAvailable())
			{
				iterator.remove();
			}
		}

		downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxService.getValue().updatePlaylist();
	}

	@Override
	public synchronized int size()
	{
		return MediaPlayerService.downloadList.size();
	}

	@Override
	public synchronized void remove(int which)
	{
		MediaPlayerService.downloadList.remove(which);
	}

	@Override
	public synchronized void remove(DownloadFile downloadFile)
	{
		if (downloadFile == MediaPlayerService.currentDownloading)
		{
			MediaPlayerService.currentDownloading.cancelDownload();
			MediaPlayerService.currentDownloading = null;
		}
		if (downloadFile == MediaPlayerService.currentPlaying)
		{
			reset();
			setCurrentPlaying(null);
		}
		MediaPlayerService.downloadList.remove(downloadFile);
		MediaPlayerService.backgroundDownloadList.remove(downloadFile);
		MediaPlayerService.revision++;
		downloadQueueSerializer.getValue().serializeDownloadQueue(getSongs(), getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxService.getValue().updatePlaylist();
		if (downloadFile == MediaPlayerService.nextPlaying)
		{
			MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
			if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
		}
	}

	@Override
	public synchronized void delete(List<MusicDirectory.Entry> songs)
	{
		for (MusicDirectory.Entry song : songs)
		{
			forSong(song).delete();
		}
	}

	@Override
	public synchronized void unpin(List<MusicDirectory.Entry> songs)
	{
		for (MusicDirectory.Entry song : songs)
		{
			forSong(song).unpin();
		}
	}

	@Override
	public synchronized int getCurrentPlayingIndex()
	{
		return MediaPlayerService.downloadList.indexOf(MediaPlayerService.currentPlaying);
	}

	@Override
	public DownloadFile getCurrentPlaying()
	{
		return MediaPlayerService.currentPlaying;
	}

	@Override
	public DownloadFile getCurrentDownloading()
	{
		return MediaPlayerService.currentDownloading;
	}

	@Override
	public List<DownloadFile> getSongs() { return MediaPlayerService.downloadList; }

	@Override
	public long getDownloadListDuration()
	{
		long totalDuration = 0;

		for (DownloadFile downloadFile : MediaPlayerService.downloadList)
		{
			Entry entry = downloadFile.getSong();

			if (!entry.isDirectory())
			{
				if (entry.getArtist() != null)
				{
					Integer duration = entry.getDuration();

					if (duration != null)
					{
						totalDuration += duration;
					}
				}
			}
		}

		return totalDuration;
	}

	@Override
	public synchronized List<DownloadFile> getDownloads()
	{
		List<DownloadFile> temp = new ArrayList<>();
		temp.addAll(MediaPlayerService.downloadList);
		temp.addAll(MediaPlayerService.backgroundDownloadList);
		return temp;
	}

	@Override
	public List<DownloadFile> getBackgroundDownloads()
	{
		return MediaPlayerService.backgroundDownloadList;
	}

	@Override
	public synchronized void previous()
	{
		int index = getCurrentPlayingIndex();
		if (index == -1)
		{
			return;
		}

		// Restart song if played more than five seconds.
		if (getPlayerPosition() > 5000 || index == 0)
		{
			play(index);
		}
		else
		{
			play(index - 1);
		}
	}

	@Override
	public synchronized void next()
	{
		int index = getCurrentPlayingIndex();
		if (index != -1)
		{
			play(index + 1);
		}
	}

	@Override
	public synchronized void reset()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.reset();
	}

	@Override
	public synchronized int getPlayerPosition()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return 0;
		return mediaPlayerService.getPlayerPosition();
	}

	@Override
	public synchronized int getPlayerDuration()
	{
		if (MediaPlayerService.currentPlaying != null)
		{
			Integer duration = MediaPlayerService.currentPlaying.getSong().getDuration();
			if (duration != null)
			{
				return duration * 1000;
			}
		}
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return 0;
		return mediaPlayerService.getPlayerDuration();
	}

	@Override
	public PlayerState getPlayerState()
	{
		return playerState;
	}

	@Override
	public void setSuggestedPlaylistName(String name)
	{
		this.suggestedPlaylistName = name;
	}

	@Override
	public String getSuggestedPlaylistName()
	{
		return suggestedPlaylistName;
	}

	@Override
	public boolean getEqualizerAvailable()	{ return MediaPlayerService.equalizerAvailable; }

	@Override
	public boolean getVisualizerAvailable()
	{
		return MediaPlayerService.visualizerAvailable;
	}

	@Override
	public EqualizerController getEqualizerController()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return null;
		return mediaPlayerService.getEqualizerController();
	}

	@Override
	public VisualizerController getVisualizerController()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return null;
		return mediaPlayerService.getVisualizerController();
	}

	@Override
	public boolean isJukeboxEnabled()
	{
		return jukeboxService.getValue().isEnabled();
	}

	@Override
	public boolean isJukeboxAvailable()
	{
		try
		{
			String username = Util.getUserName(context, Util.getActiveServer(context));
			UserInfo user = MusicServiceFactory.getMusicService(context).getUser(username, context, null);
			return user.getJukeboxRole();
		}
		catch (Exception e)
		{
			Log.w(TAG, "Error getting user information", e);
		}

		return false;
	}

	@Override
	public boolean isSharingAvailable()
	{
		try
		{
			String username = Util.getUserName(context, Util.getActiveServer(context));
			UserInfo user = MusicServiceFactory.getMusicService(context).getUser(username, context, null);
			return user.getShareRole();
		}
		catch (Exception e)
		{
			Log.w(TAG, "Error getting user information", e);
		}

		return false;
	}

	@Override
	public void setJukeboxEnabled(boolean jukeboxEnabled)
	{
		jukeboxService.getValue().setEnabled(jukeboxEnabled);
		setPlayerState(PlayerState.IDLE);

		if (jukeboxEnabled)
		{
			jukeboxService.getValue().startJukeboxService();

			reset();

			// Cancel current download, if necessary.
			if (MediaPlayerService.currentDownloading != null)
			{
				MediaPlayerService.currentDownloading.cancelDownload();
			}
		}
		else
		{
			jukeboxService.getValue().stopJukeboxService();
		}
	}

	@Override
	public void adjustJukeboxVolume(boolean up)
	{
		jukeboxService.getValue().adjustVolume(up);
	}

	@Override
	public void setVolume(float volume)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setVolume(volume);
	}

	@Override
	public synchronized void swap(boolean mainList, int from, int to)
	{
		List<DownloadFile> list = mainList ? MediaPlayerService.downloadList : MediaPlayerService.backgroundDownloadList;
		int max = list.size();

		if (to >= max)
		{
			to = max - 1;
		}
		else if (to < 0)
		{
			to = 0;
		}

		int currentPlayingIndex = getCurrentPlayingIndex();
		DownloadFile movedSong = list.remove(from);
		list.add(to, movedSong);

		if (jukeboxService.getValue().isEnabled() && mainList)
		{
			jukeboxService.getValue().updatePlaylist();
		}
		else if (mainList && (movedSong == MediaPlayerService.nextPlaying || (currentPlayingIndex + 1) == to))
		{
			// Moving next playing or moving a song to be next playing
			MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
			if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
		}
	}

	@Override
	public long getDownloadListUpdateRevision()
	{
		return MediaPlayerService.revision;
	}

	@Override
	public void updateNotification()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.updateNotification();
	}

    public void setSongRating(final int rating)
	{
		if (!KoinJavaComponent.get(FeatureStorage.class).isFeatureEnabled(Feature.FIVE_STAR_RATING))
			return;

		if (MediaPlayerService.currentPlaying == null)
			return;

		final Entry song = MediaPlayerService.currentPlaying.getSong();
		song.setUserRating(rating);

		new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					MusicServiceFactory.getMusicService(context).setRating(song.getId(), rating, context, null);
				}
				catch (Exception e)
				{
					Log.e(TAG, e.getMessage(), e);
				}
			}
		}).start();

		updateNotification();
	}
}