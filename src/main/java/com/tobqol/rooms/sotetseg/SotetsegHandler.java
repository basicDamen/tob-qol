/*
 * Copyright (c) 2022, Damen <gh: damencs>
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
package com.tobqol.rooms.sotetseg;

import com.tobqol.TheatreQOLConfig;
import com.tobqol.TheatreQOLPlugin;
import com.tobqol.api.game.Region;
import com.tobqol.rooms.RoomHandler;
import com.tobqol.rooms.sotetseg.commons.MutableMaze;
import com.tobqol.rooms.sotetseg.commons.SotetsegTable;
import com.tobqol.rooms.sotetseg.config.SotetsegProjectileTheme;
import com.tobqol.tracking.RoomDataHandler;
import com.tobqol.tracking.RoomDataItem;
import com.tobqol.tracking.RoomInfoBox;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.sound.sampled.*;
import java.awt.*;

import static com.tobqol.api.game.Region.SOTETSEG;
import static com.tobqol.rooms.sotetseg.commons.SotetsegConstants.*;
import static com.tobqol.rooms.sotetseg.commons.SotetsegTable.SOTETSEG_CLICKABLE;
import static com.tobqol.tracking.RoomInfoUtil.createInfoBox;
import static com.tobqol.tracking.RoomInfoUtil.formatTime;

@Slf4j
public class SotetsegHandler extends RoomHandler
{
	@Inject
	private SotetsegSceneOverlay sceneOverlay;

	private RoomDataHandler dataHandler;

	@Getter
	@CheckForNull
	private NPC sotetsegNpc = null;

	private RoomInfoBox sotetsegInfoBox;

	@Getter
	private boolean clickable = false;

	@Getter
	@CheckForNull
	private MutableMaze maze = null;

	@Getter
	@CheckForNull
	private GameObject portal = null;

	private boolean considerTeleport = true;

	private static Clip soundClip;

	@Inject
	protected SotetsegHandler(TheatreQOLPlugin plugin, TheatreQOLConfig config)
	{
		super(plugin, config);
		setRoomRegion(Region.SOTETSEG);

		dataHandler = plugin.getDataHandler();
	}

	@Override
	public void load()
	{
		overlayManager.add(sceneOverlay);
		soundClip = generateSoundClip("weewoo-hoyaa.wav", config.sotetsegSoundClipVolume());
	}

	@Override
	public void unload()
	{
		overlayManager.remove(sceneOverlay);
		reset();
	}

	@Override
	public boolean active()
	{
		return instance.getCurrentRegion().isSotetseg() && sotetsegNpc != null && !sotetsegNpc.isDead();
	}

	@Override
	public void reset()
	{
		sotetsegNpc = null;
		clickable = false;
		maze = null;
		portal = null;
		considerTeleport = true;

		if (instance.getRaidStatus() <= 1)
		{
			infoBoxManager.removeInfoBox(sotetsegInfoBox);
		}
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged e)
	{
		if (!e.getGroup().equals(TheatreQOLConfig.GROUP_NAME) || !instance.getCurrentRegion().isSotetsegUnderworld())
		{
			return;
		}

		switch (e.getKey())
		{
			case "sotetsegHideUnderworldRocks":
			{
				when(config.sotetsegHideUnderworldRocks(), this::hideUnderworldRocks, sceneManager::refreshScene);
				break;
			}

			case "sotetsegSoundClipVolume":
			{
				if (soundClip != null && config.sotetsegSoundClip())
				{
					FloatControl control = (FloatControl) soundClip.getControl(FloatControl.Type.MASTER_GAIN);

					if (control != null)
					{
						control.setValue((float)(config.sotetsegSoundClipVolume() / 2 - 45));
					}

					soundClip.setFramePosition(0);
					soundClip.start();
				}
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (instance.isInRaid() && instance.getCurrentRegion().isSotetseg())
		{
			if (instance.getRoomStatus() == 1 && !dataHandler.Find("Starting Tick").isPresent())
			{
				dataHandler.getData().add(new RoomDataItem("Starting Tick", client.getTickCount(), true, true));
				dataHandler.setShouldTrack(true);

				dataHandler.getData().add(new RoomDataItem("Room", dataHandler.getTime(), 99, false, "33%"));
			}

			if (dataHandler.isShouldTrack() && !dataHandler.getData().isEmpty())
			{
				dataHandler.updateTotalTime();
			}
		}

		if (!active())
		{
			return;
		}

		if (!considerTeleport)
		{
			considerTeleport = true;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!active())
		{
			return;
		}

		when(config.sotetsegHideUnderworldRocks(), this::hideUnderworldRocks, null);
	}

	@Subscribe
	private void onNpcSpawned(NpcSpawned e)
	{
		isNpcFromName(e.getNpc(), BOSS_NAME, n ->
		{
			instance.lazySetMode(() -> SotetsegTable.findMode(n.getId()));
			sotetsegNpc = n;
			clickable = SotetsegTable.anyMatch(SOTETSEG_CLICKABLE, n.getId());
		});
	}

	@Subscribe
	private void onNpcChanged(NpcChanged e)
	{
		if (!active())
		{
			return;
		}

		isNpcFromName(e.getNpc(), BOSS_NAME, n ->
		{
			if (clickable = SotetsegTable.anyMatch(SOTETSEG_CLICKABLE, n.getId()))
			{
				if (!dataHandler.Find("Starting Tick").isPresent())
				{
					dataHandler.getData().add(new RoomDataItem("Starting Tick", client.getTickCount(), true));
					dataHandler.setShouldTrack(true);
				}

				log.debug("[{}] - Sotetseg Changed NPC IDs -> Clickable: {}", client.getTickCount(), clickable);
			}
		});
	}

	@Subscribe
	private void onNpcDespawned(NpcDespawned e)
	{
		if (!active() || client.getPlane() != 0)
		{
			return;
		}

		isNpcFromName(e.getNpc(), BOSS_NAME, n -> reset());
	}

	@Subscribe
	private void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		if (!active())
		{
			return;
		}

		GroundObject obj = e.getGroundObject();

		if (SotetsegTable.isActiveMazeObject(obj))
		{
			if (maze == null)
			{
				maze = new MutableMaze(instance);
			}

			WorldPoint wp = WorldPoint.fromLocal(client, e.getTile().getLocalLocation());
			maze.addPoint(wp.getRegionX(), wp.getRegionY());
		}
	}

	@Subscribe
	private void onGameObjectSpawned(GameObjectSpawned e)
	{
		if (!active() || portal != null)
		{
			return;
		}

		GameObject obj = e.getGameObject();

		if (obj.getId() == SotetsegTable.MAZE_UNDERWORLD_PORTAL)
		{
			portal = obj;
		}

		if (UNDERWORLD_ROCKS.contains(obj.getId()))
		{
			when(config.sotetsegHideUnderworldRocks(), this::hideUnderworldRocks, null);
		}
	}

	@Subscribe
	private void onGameObjectDespawned(GameObjectDespawned e)
	{
		if (!active() || portal == null)
		{
			return;
		}

		if (e.getGameObject().getId() == SotetsegTable.MAZE_UNDERWORLD_PORTAL)
		{
			portal = null;
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved)
	{
		if (!instance.getCurrentRegion().isSotetseg())
		{
			return;
		}

		SotetsegProjectileTheme theme = config.getSotetsegProjectileTheme();

		Projectile projectile = projectileMoved.getProjectile();
		int projectileId = projectile.getId();

		if (projectileId == DEATH_BALL && config.sotetsegSoundClip())
		{
			soundClip.setFramePosition(0);
			soundClip.start();
		}

		if (!theme.isDefault() && (projectileId != DEATH_BALL || (projectileId == DEATH_BALL && theme.isInfernoTheme())))
		{
			int replacement = -1;

			switch (projectileId)
			{
				case RANGE_ORB:
				{
					switch (theme)
					{
						case INFERNO:
							replacement = INFERNO_RANGE;
							break;
					}
					break;
				}
				case MAGIC_ORB:
				{
					switch (theme)
					{
						case INFERNO:
							replacement = INFERNO_MAGE;
							break;
					}
					break;
				}
				case DEATH_BALL:
				{
					switch (theme)
					{
						case INFERNO:
							if (config.infernoThemeZukBall())
							{
								replacement = INFERNO_DEATH_BALL;
								break;
							}

					}
				}
			}

			if (replacement == -1)
			{
				return;
			}

			Projectile p = client.createProjectile(replacement,
					projectile.getFloor(),
					projectile.getX1(), projectile.getY1(),
					projectile.getHeight(),
					projectile.getStartCycle(), projectile.getEndCycle(),
					projectile.getSlope(),
					projectile.getStartHeight(), projectile.getEndHeight(),
					projectile.getInteracting(),
					projectile.getTarget().getX(), projectile.getTarget().getY());

			client.getProjectiles().addLast(p);
			projectile.setEndCycle(0);
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (active())
		{
			if (considerTeleport && event.getActor().getAnimation() == MAZE_TELE_ANIM)
			{
				boolean phase = dataHandler.Find("66%").isPresent();
				dataHandler.getData().add(new RoomDataItem(phase ? "33%" : "66%", dataHandler.getTime(), phase ? 2 : 1, false));
				considerTeleport = false;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (instance.getCurrentRegion() != SOTETSEG && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String stripped = Text.removeTags(event.getMessage());

		if (SOTETSEG_WAVE.matcher(stripped).find())
		{
			dataHandler.setShouldTrack(false);
			dataHandler.Find("Room").get().setValue(dataHandler.getTime());

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
		if (!dataHandler.getData().isEmpty())
		{
			String tooltip;

			if (!dataHandler.Find("Starting Tick").get().isException())
			{
				tooltip = "66% - " + formatTime(dataHandler.FindValue("66%")) + "</br>" +
						"33% - " + formatTime(dataHandler.FindValue("33%")) + formatTime(dataHandler.FindValue("33%"), dataHandler.FindValue("66%")) + "</br>" +
						"Complete - " + formatTime(dataHandler.FindValue("Room")) + formatTime(dataHandler.FindValue("Room"), dataHandler.FindValue("33%"));
			}
			else
			{
				tooltip = "Complete - " + formatTime(dataHandler.FindValue("Room")) + "*";
			}

			sotetsegInfoBox = createInfoBox(plugin, config, itemManager.getImage(BOSS_IMAGE), "Sotetseg", formatTime(dataHandler.FindValue("Room")), tooltip);
			infoBoxManager.addInfoBox(sotetsegInfoBox);
		}
	}

	private void sendChatTimes()
	{
		if (!dataHandler.getData().isEmpty())
		{
			if (!dataHandler.Find("Starting Tick").get().isException())
			{
				enqueueChatMessage(ChatMessageType.GAMEMESSAGE, b -> b
						.append(Color.RED, "66%")
						.append(ChatColorType.NORMAL)
						.append(" - " + formatTime(dataHandler.FindValue("66%")) + " - ")
						.append(Color.RED, "33%")
						.append(ChatColorType.NORMAL)
						.append(" - " + formatTime(dataHandler.FindValue("33%")) + formatTime(dataHandler.FindValue("33%"), dataHandler.FindValue("66%"))));

				if (config.roomTimeValidation())
				{
					enqueueChatMessage(ChatMessageType.GAMEMESSAGE, b -> b
							.append(Color.RED, "Sotetseg - Room Complete")
							.append(ChatColorType.NORMAL)
							.append(" - " + formatTime(dataHandler.FindValue("Room")) + formatTime(dataHandler.FindValue("Room"), dataHandler.FindValue("33%"))));
				}
			}
			else
			{
				if (config.roomTimeValidation())
				{
					enqueueChatMessage(ChatMessageType.GAMEMESSAGE, b -> b
							.append(Color.RED, "Sotetseg - Room Complete")
							.append(ChatColorType.NORMAL)
							.append(" - " + formatTime(dataHandler.FindValue("Room")) + "*"));
				}
			}
		}
	}

	private void hideUnderworldRocks()
	{
		if (instance.getCurrentRegion().isSotetsegUnderworld())
		{
			sceneManager.removeTheseGameObjects(3, UNDERWORLD_ROCKS);
		}
	}
}
