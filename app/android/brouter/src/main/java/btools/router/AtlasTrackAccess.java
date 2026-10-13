/*
 * Atlas addition to the vendored BRouter core (MIT, like the rest of this module).
 * It lives in package btools.router only to read the package-private voice hints and per-node messages
 * of a finished OsmTrack; it changes nothing in BRouter itself.
 */
package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;

/**
 * Copies what Atlas needs from a finished {@link OsmTrack} into plain public arrays and objects:
 * the points, elevations, times and energies, the way and node tags of every link, and the voice hints.
 */
public final class AtlasTrackAccess {

  /** Voice-hint commands, as BRouter numbers them internally. */
  public static final int C = VoiceHint.C;
  public static final int TL = VoiceHint.TL;
  public static final int TSLL = VoiceHint.TSLL;
  public static final int TSHL = VoiceHint.TSHL;
  public static final int TR = VoiceHint.TR;
  public static final int TSLR = VoiceHint.TSLR;
  public static final int TSHR = VoiceHint.TSHR;
  public static final int KL = VoiceHint.KL;
  public static final int KR = VoiceHint.KR;
  public static final int TLU = VoiceHint.TLU;
  public static final int TRU = VoiceHint.TRU;
  public static final int OFFR = VoiceHint.OFFR;
  public static final int RNDB = VoiceHint.RNDB;
  public static final int RNLB = VoiceHint.RNLB;
  public static final int TU = VoiceHint.TU;
  public static final int BL = VoiceHint.BL;
  public static final int EL = VoiceHint.EL;
  public static final int ER = VoiceHint.ER;
  public static final int END = VoiceHint.END;

  private AtlasTrackAccess() {
  }

  /** One voice hint: the command at a track index, with its exit, angle and the ways around it. */
  public static final class Hint {
    public final int index;
    public final int command;
    /** Roundabout exit: positive counter-clockwise (RNDB), negative clockwise (RNLB), 0 otherwise. */
    public final int exit;
    /** Turn angle in degrees, negative to the left; NaN when BRouter did not compute one. */
    public final double angle;
    public final double distanceToNext;
    public final boolean giveWay;
    /** Tags of the way taken after the hint and of the way before it ("k=v k=v"), or null. */
    public final String wayTags;
    public final String previousWayTags;
    public final int priority;
    public final int previousPriority;
    public final boolean link;
    public final boolean previousLink;
    public final boolean roundabout;
    /** How many other ways leave the junction (BRouter's "bad ways"). */
    public final int sideWays;

    Hint(VoiceHint h) {
      index = h.indexInTrack;
      command = h.cmd;
      exit = h.roundaboutExit;
      angle = h.angle == Float.MAX_VALUE ? Double.NaN : h.angle;
      distanceToNext = h.distanceToNext;
      giveWay = h.oldWay != null && h.oldWay.wayKeyValues != null && h.hasGiveWay();
      wayTags = h.goodWay == null ? null : h.goodWay.wayKeyValues;
      previousWayTags = h.oldWay == null ? null : h.oldWay.wayKeyValues;
      priority = h.goodWay == null ? 0 : h.goodWay.priorityclassifier;
      previousPriority = h.oldWay == null ? 0 : h.oldWay.priorityclassifier;
      link = h.goodWay != null && h.goodWay.isLinktType();
      previousLink = h.oldWay != null && h.oldWay.isLinktType();
      roundabout = h.goodWay != null && h.goodWay.isRoundabout();
      sideWays = h.badWays == null ? 0 : h.badWays.size();
    }
  }

  /**
   * A finished track as arrays indexed by point. The link arrays ({@link #wayTags}, {@link #priority}, …) describe
   * the link that ends at a point, so index 0 is usually empty.
   */
  public static final class Track {
    public final int size;
    public final int[] ilon;
    public final int[] ilat;
    /** Elevation in quarter metres, or {@code Short.MIN_VALUE} where unknown. */
    public final short[] selev;
    /** Seconds and joules from the start. */
    public final float[] time;
    public final float[] energy;
    public final String[] wayTags;
    public final String[] nodeTags;
    public final int[] priority;
    public final int[] classifierMask;
    public final float[] costFactor;
    public final List<Hint> hints;
    /**
     * Track indices of the matched waypoints, in order, with their names ("from", "via1", …, "to"; BRouter adds
     * "_add" and "_2" points when it joins a far-away point to the network with a straight line).
     */
    public final int[] waypointIndices;
    public final String[] waypointNames;
    public final int distance;
    public final int ascend;
    public final int plainAscend;
    public final int cost;
    public final int totalEnergy;
    public final int totalSeconds;

    Track(OsmTrack t) {
      List<OsmPathElement> nodes = t.nodes;
      size = nodes.size();
      ilon = new int[size];
      ilat = new int[size];
      selev = new short[size];
      time = new float[size];
      energy = new float[size];
      wayTags = new String[size];
      nodeTags = new String[size];
      priority = new int[size];
      classifierMask = new int[size];
      costFactor = new float[size];
      for (int i = 0; i < size; i++) {
        OsmPathElement e = nodes.get(i);
        ilon[i] = e.getILon();
        ilat[i] = e.getILat();
        selev[i] = e.getSElev();
        time[i] = e.getTime();
        energy[i] = e.getEnergy();
        MessageData m = e.message;
        if (m != null) {
          wayTags[i] = m.wayKeyValues;
          nodeTags[i] = m.nodeKeyValues;
          priority[i] = m.priorityclassifier;
          classifierMask[i] = m.classifiermask;
          costFactor[i] = m.costfactor;
        }
      }
      List<Hint> hs = new ArrayList<>();
      if (t.voiceHints != null) {
        for (VoiceHint h : t.voiceHints.list) {
          hs.add(new Hint(h));
        }
      }
      hints = Collections.unmodifiableList(hs);
      List<MatchedWaypoint> mw = t.matchedWaypoints;
      if (mw == null) {
        waypointIndices = new int[0];
        waypointNames = new String[0];
      } else {
        int[] idx = new int[mw.size()];
        String[] names = new String[mw.size()];
        for (int i = 0; i < idx.length; i++) {
          idx[i] = mw.get(i).indexInTrack;
          names[i] = mw.get(i).name;
        }
        waypointIndices = idx;
        waypointNames = names;
      }
      distance = t.distance;
      ascend = t.ascend;
      plainAscend = t.plainAscend;
      cost = t.cost;
      totalEnergy = t.energy;
      totalSeconds = t.getTotalSeconds();
    }
  }

  /** Copies a finished track; the track itself is left untouched. */
  public static Track read(OsmTrack track) {
    return new Track(track);
  }

  /** BRouter's short name for a command, e.g. "TSLL" or "RNDB". */
  public static String commandName(int command) {
    switch (command) {
      case C: return "C";
      case TL: return "TL";
      case TSLL: return "TSLL";
      case TSHL: return "TSHL";
      case TR: return "TR";
      case TSLR: return "TSLR";
      case TSHR: return "TSHR";
      case KL: return "KL";
      case KR: return "KR";
      case TLU: return "TLU";
      case TRU: return "TRU";
      case OFFR: return "OFFR";
      case RNDB: return "RNDB";
      case RNLB: return "RNLB";
      case TU: return "TU";
      case BL: return "BL";
      case EL: return "EL";
      case ER: return "ER";
      case END: return "END";
      default: return "?" + command;
    }
  }
}
