/*
 * BedWars1058 - A bed wars mini-game.
 * Copyright (C) 2021 Andrei Dascălu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Contact e-mail: andrew.dascalu@gmail.com
 */

package com.andrei1058.bedwars.arena.spectator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public final class SpectatorItemsGUI {

    private static final Map<Player, Inventory> inventories = new HashMap<>();

    private SpectatorItemsGUI() {
    }

    public static void open(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 36, ChatColor.DARK_GRAY + "Spectator Items");
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null) {
                inventory.setItem(slot, item.clone());
                player.getInventory().setItem(slot, null);
            }
        }
        inventories.put(player, inventory);
        player.openInventory(inventory);
    }

    public static Inventory getInventory(Player player) {
        return inventories.get(player);
    }

    public static void close(Player player) {
        inventories.remove(player);
    }
}
