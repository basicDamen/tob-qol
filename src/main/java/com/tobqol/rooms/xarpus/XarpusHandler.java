/*
 * Copyright (c) 2022, Damen <gh: damencs>
 * Copyright (c) 2022, Boris - Portions of Area Sound Effects Played
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tobqol.rooms.xarpus;

import com.tobqol.TheatreQOLConfig;
import com.tobqol.TheatreQOLPlugin;
import com.tobqol.api.game.Region;
import com.tobqol.config.times.TimeDisplayDetail;
import com.tobqol.rooms.RoomHandler;
import com.tobqol.rooms.xarpus.commons.ExhumedTracker;
import com.tobqol.rooms.xarpus.commons.XarpusConstants;
import com.tobqol.rooms.xarpus.commons.XarpusPhase;
import com.tobqol.rooms.xarpus.commons.XarpusTable;
import com.tobqol.tracking.RoomDataItem;
import com.tobqol.tracking.RoomInfoBox;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPC;
import net.runelite.api.events.*;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.sound.sampled.*;
import java.awt.*;
import java.io.BufferedInputStream;

import static com.tobqol.api.game.Region.VERZIK;
import static com.tobqol.api.game.Region.XARPUS;
import static com.tobqol.rooms.xarpus.commons.XarpusConstants.BOSS_IMAGE;
import static com.tobqol.rooms.xarpus.commons.XarpusConstants.XARPUS_WAVE;
import static com.tobqol.tracking.RoomInfoUtil.createInfoBox;
import static com.tobqol.tracking.RoomInfoUtil.formatTime;

@Slf4j
public class XarpusHandler extends RoomHandler
{
	@Inject
	private XarpusSceneOverlay sceneOverlay;

	@Getter
	@CheckForNull
	private NPC xarpusNpc = null;

	private RoomInfoBox xarpuInfoBox;

	@Getter
	private XarpusPhase phase = XarpusPhase.UNKNOWN;

	@Getter
	@CheckForNull
	private ExhumedTracker exhumedTracker = null;

	private static Clip soundClip;

	@Inject
	protected XarpusHandler(TheatreQOLPlugin plugin, TheatreQOLConfig config)
	{
		super(plugin, config);
		setRoomRegion(Region.XARPUS);
	}

	public void init()
	{
		try
		{
			AudioInputStream stream = AudioSystem.getAudioInputStream(new BufferedInputStream(TheatreQOLPlugin.class.getResourceAsStream("sheesh-hoyaa.wav")));
			AudioFormat format = stream.getFormat();
			DataLine.Info info = new DataLine.Info(Clip.class, format);
			soundClip = (Clip)AudioSystem.getLine(info);
			soundClip.open(stream);
			FloatControl control = (FloatControl) soundClip.getControl(FloatControl.Type.MASTER_GAIN);

			if (control != null)
			{
				control.setValue((float)(config.xarpusSoundClipVolume() / 2 - 45));
			}
		}
		catch (Exception ex)
		{
			soundClip = null;
		}
	}

	@Override
	public void load()
	{
		overlayManager.add(sceneOverlay);
		overlayManager.add(getTimeOverlay());
	}

	@Override
	public void unload()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(getTimeOverlay());
		reset();
	}

	@Override
	public boolean active()
	{
		return instance.getCurrentRegion().isXarpus() && xarpusNpc != null && !xarpusNpc.isDead();
	}

	@Override
	public void reset()
	{
		xarpusNpc = null;
		phase = XarpusPhase.UNKNOWN;

		if (exhumedTracker != null)
		{
			exhumedTracker = null;
		}

		if (instance.getRaidStatus() <= 1)
		{
			infoBoxManager.removeInfoBox(xarpuInfoBox);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("tobqol"))
		{
			if (event.getKey().equals("xarpusSoundClipVolume") && config.xarpusSoundClip())
			{
				if (soundClip != null)
				{
					FloatControl control = (FloatControl) soundClip.getControl(FloatControl.Type.MASTER_GAIN);

					if (control != null)
					{
						control.setValue((float)(config.xarpusSoundClipVolume() / 2 - 45));
					}

					soundClip.setFramePosition(0);
					soundClip.start();
				}
			}
		}
	}

	@Subscribe
	private void onNpcSpawned(NpcSpawned e)
	{
		if (active())
		{
			return;
		}

		isNpcFromName(e.getNpc(), XarpusConstants.BOSS_NAME, n ->
		{
			instance.lazySetMode(() -> XarpusTable.findMode(n.getId()));
			xarpusNpc = n;
			phase = XarpusPhase.compose(n);

			if (phase == XarpusPhase.P2 && (n.getOverheadText() != null || client.getVarbitValue(6448) <= 250))
			{
				phase = XarpusPhase.P3;
			}

			exhumedTracker = new ExhumedTracker();
		});
	}

	@Subscribe
	private void onNpcChanged(NpcChanged e)
	{
		if (!active())
		{
			return;
		}

		isNpcFromName(e.getNpc(), XarpusConstants.BOSS_NAME, n ->
		{
			phase = XarpusPhase.compose(n);

			if (XarpusTable.anyMatch(XarpusTable.XARPUS_P1, n.getId()))
			{
				if (!Find("Starting Tick").isPresent())
				{
					getData().add(new RoomDataItem("Starting Tick", client.getTickCount(), true));
					setShouldTrack(true);
					return;
				}
			}
			else if (XarpusTable.anyMatch(XarpusTable.XARPUS_P23, n.getId()))
			{
				if (!Find("Exhumeds").isPresent())
				{
					getData().add(new RoomDataItem("Exhumeds", getTime(), 1, false));
					return;
				}
			}
		});
	}

	@Subscribe
	private void onNpcDespawned(NpcDespawned e)
	{
		if (!active() || xarpusNpc == null || !isNpcFromName(e.getNpc(), XarpusConstants.BOSS_NAME))
		{
			return;
		}

		reset();
	}

	@Subscribe
	private void onGameTick(GameTick e)
	{
		if ((!instance.isInRaid() || instance.getCurrentRegion() == VERZIK) && !getData().isEmpty())
		{
			getData().clear();
		}

		if (instance.isInRaid() && instance.getRoomStatus() == 1 && instance.getCurrentRegion().isXarpus() && !Find("Starting Tick").isPresent())
		{
			getData().add(new RoomDataItem("Starting Tick", getTime(), true));
			setShouldTrack(true);
		}

		if (isShouldTrack() && !getData().isEmpty())
		{
			updateTotalTime();
		}

		if (!active())
		{
			return;
		}

		if (exhumedTracker != null)
		{
			exhumedTracker.tick();

			if (exhumedTracker.getExhumeds().isEmpty() && !phase.isInactiveOrP1())
			{
				exhumedTracker = null;
			}
		}

		if (xarpusNpc.getOverheadText() != null && !phase.isP3())
		{
			phase = XarpusPhase.P3;
		}
	}

	@Subscribe
	private void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		if (!active() || exhumedTracker == null)
		{
			return;
		}

		if (exhumedTracker.track(e.getGroundObject()) && instance.getTickCycle() > -1)
		{
			instance.resetTickCycle();
		}
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if(xarpusNpc != null && active())
		{
			if (event.getSoundId() == 4005 && instance.isHardMode() && config.muteXarpusHMEntry())
			{
				event.consume();
			}
			else if (event.getSoundId() == 4007 && config.xarpusSoundClip())
			{
				event.consume();
			}
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (active() && event.getActor() instanceof NPC && config.xarpusSoundClip())
		{
			if (xarpusNpc == event.getActor())
			{
				event.getActor().setOverheadText("Sheeeeeesh!");
				soundClip.setFramePosition(0);
				soundClip.start();

				getData().add(new RoomDataItem("Screech", getTime(), 2, false));
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (instance.getCurrentRegion() != XARPUS && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String stripped = Text.removeTags(event.getMessage());

		if (XARPUS_WAVE.matcher(stripped).find())
		{
			setShouldTrack(false);
			Find("Total Time").get().setValue(getTime());

			if (config.displayRoomTimes().isInfobox())
			{
				buildInfobox();
			}

			if (config.displayRoomTimes().isChat())
			{
				sendChatTimes();
			}
		}
	}

	private void buildInfobox()
	{
		if (!getData().isEmpty())
		{
			boolean detailed = config.displayRoomTimesDetail() == TimeDisplayDetail.DETAILED;

			String tooltip = "Exhumeds - " + formatTime(FindValue("Exhumeds"), detailed) + "</br>" +
					"Screech - " + formatTime(FindValue("Screech"), detailed) + formatTime(FindValue("Screech"), FindValue("Exhumeds"), detailed) + "</br>" +
					"Complete - " + formatTime(FindValue("Total Time"), detailed) + formatTime(FindValue("Total Time"), FindValue("Screech"), detailed);

			xarpuInfoBox = createInfoBox(plugin, config, itemManager.getImage(BOSS_IMAGE), "Xarpus", formatTime(FindValue("Total Time"), detailed), tooltip);
			infoBoxManager.addInfoBox(xarpuInfoBox);
		}
	}

	private void sendChatTimes()
	{
		if (!getData().isEmpty())
		{
			boolean detailed = config.displayRoomTimesDetail() == TimeDisplayDetail.DETAILED;

			enqueueChatMessage(ChatMessageType.GAMEMESSAGE, b -> b
					.append(Color.RED, "Exhumeds")
					.append(ChatColorType.NORMAL)
					.append(" - " + formatTime(FindValue("Exhumeds"), detailed) + " - ")
					.append(Color.RED, "Screech")
					.append(ChatColorType.NORMAL)
					.append(" - " + formatTime(FindValue("Screech"), detailed) + formatTime(FindValue("Screech"), FindValue("Exhumeds"), detailed)));

			if (config.roomTimeValidation())
			{
				enqueueChatMessage(ChatMessageType.GAMEMESSAGE, b -> b
						.append(Color.RED, "Xarpus - Room Complete")
						.append(ChatColorType.NORMAL)
						.append(" - " + formatTime(FindValue("Total Time"), detailed) + " - " + formatTime(FindValue("Total Time"), FindValue("Screech"), detailed)));
			}
		}
	}
}
