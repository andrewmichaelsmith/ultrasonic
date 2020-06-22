package org.moire.ultrasonic.service;

import org.moire.ultrasonic.domain.MusicDirectory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class State implements Serializable
{
    public static final long serialVersionUID = -6346438781062572270L;

    public List<MusicDirectory.Entry> songs = new ArrayList<MusicDirectory.Entry>();
    public int currentPlayingIndex;
    public int currentPlayingPosition;
}
