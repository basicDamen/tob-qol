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
package com.tobqol.rooms.verzik;

import com.tobqol.TheatreQOLConfig;
import com.tobqol.TheatreQOLPlugin;
import com.tobqol.api.game.Instance;
import com.tobqol.rooms.RoomSceneOverlay;
import com.tobqol.rooms.verzik.commons.VerzikMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayUtil;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import java.awt.*;
import java.text.DecimalFormat;

@Slf4j
public class VerzikOverlay extends RoomSceneOverlay<VerzikHandler>
{
	private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#0.0");

	@Inject
	protected VerzikOverlay(
			Client client,
			Instance instance,
			VerzikHandler room,
			TheatreQOLPlugin plugin,
			TheatreQOLConfig config
	)
	{
		super(client, instance, room, plugin, config);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!instance.getCurrentRegion().isVerzik())
		{
			return null;
		}

		NPC verzikNpc = room.getVerzikNpc();

		if (verzikNpc == null)
		{
			return null;
		}

		VerzikMap def = VerzikMap.queryTable(verzikNpc.getId());

		if (def == null)
		{
			return null;
		}

		setLayer(OverlayLayer.ABOVE_SCENE);
		graphics.setFont(plugin.getPluginFont());

		switch (def)
		{
			case VERZIK_P2:
				displayRedCrabs(graphics);
				break;
			case VERZIK_P3:
				displayTornadoes(graphics);
		}

		return null;
	}

	private void displayTornadoes(Graphics2D graphics)
	{
		if (!config.shouldMarkVerzikTornadoes() || room.getTornadoes().isEmpty())
		{
			return;
		}

		room.getTornadoes().forEach(t ->
		{
			Color color = config.verzikMarkedTornadoColor();
			t.first(client).ifPresent(p -> OverlayUtil.renderPolygon(graphics, p, color));
			t.second(client).ifPresent(p -> OverlayUtil.renderPolygon(graphics, p, color.darker()));
		});
	}

	private void displayRedCrabs(Graphics2D graphics)
	{
		if (config.verzikReds())
		{
			// First loop: Draw the percentages over the crabs
			room.getVerzikReds().forEach((crab, v) ->
			{
				int v_health = v.getValue();           // Stored health scale
				int v_healthRatio = v.getKey();        // Stored health ratio

				int currentHealthScale = crab.getHealthScale();
				int currentHealthRatio = crab.getHealthRatio();

				// Only update if current health info is valid
				if (crab.getName() != null && currentHealthScale > 0 && currentHealthRatio > 0)
				{
					v_health = currentHealthScale;
					v_healthRatio = Math.min(v_healthRatio, currentHealthRatio);

					// Update the stored values
					room.getVerzikReds().put(crab, new MutablePair<>(v_healthRatio, v_health));
				}
				// Else, keep using the stored v_health and v_healthRatio

				String percentage = DECIMAL_FORMAT.format(((float) v_healthRatio / (float) v_health) * 100f);

				Point textLocation = crab.getCanvasTextLocation(graphics, percentage, 80);

				if (!crab.isDead() && textLocation != null)
				{
					OverlayUtil.renderTextLocation(graphics, textLocation, percentage, Color.WHITE);
				}
			});

			// Second loop: Update stored values if current health info is valid
			NPC[] reds = room.getVerzikReds().keySet().toArray(new NPC[0]);
			for (NPC npc : reds)
			{
				int currentHealthScale = npc.getHealthScale();
				int currentHealthRatio = npc.getHealthRatio();

				if (npc.getName() != null && currentHealthScale > 0 && currentHealthRatio > 0 && currentHealthRatio < 100)
				{
					Pair<Integer, Integer> newVal = new MutablePair<>(currentHealthRatio, currentHealthScale);
					room.getVerzikReds().put(npc, newVal);
				}
				// If current health ratio is invalid, do not update the stored values
			}
		}
	}
}
