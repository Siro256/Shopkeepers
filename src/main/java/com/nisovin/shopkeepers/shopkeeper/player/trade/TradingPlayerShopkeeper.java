package com.nisovin.shopkeepers.shopkeeper.player.trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.Settings;
import com.nisovin.shopkeepers.api.ShopkeepersPlugin;
import com.nisovin.shopkeepers.api.shopkeeper.ShopkeeperCreateException;
import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import com.nisovin.shopkeepers.api.shopkeeper.player.PlayerShopCreationData;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.shopkeeper.SKDefaultShopTypes;
import com.nisovin.shopkeepers.shopkeeper.offers.TradingOffer;
import com.nisovin.shopkeepers.shopkeeper.player.AbstractPlayerShopkeeper;
import com.nisovin.shopkeepers.util.ItemCount;
import com.nisovin.shopkeepers.util.ItemUtils;

public class TradingPlayerShopkeeper extends AbstractPlayerShopkeeper {

	protected static class TradingPlayerShopEditorHandler extends PlayerShopEditorHandler {

		protected TradingPlayerShopEditorHandler(TradingPlayerShopkeeper shopkeeper) {
			super(shopkeeper);
		}

		@Override
		public TradingPlayerShopkeeper getShopkeeper() {
			return (TradingPlayerShopkeeper) super.getShopkeeper();
		}

		@Override
		protected boolean openWindow(Player player) {
			TradingPlayerShopkeeper shopkeeper = this.getShopkeeper();
			Inventory inventory = Bukkit.createInventory(player, 27, Settings.editorTitle);

			// add the shopkeeper's offers:
			List<ItemCount> chestItems = shopkeeper.getItemsFromChest();
			for (int column = 0; column < chestItems.size() && column < TRADE_COLUMNS; column++) {
				ItemCount itemCount = chestItems.get(column);
				ItemStack tradedItem = itemCount.getItem(); // this item is already a copy with amount 1
				TradingOffer offer = shopkeeper.getOffer(tradedItem);
				if (offer != null) {
					// adjust traded item amount:
					tradedItem.setAmount(offer.getResultItem().getAmount());

					// fill in costs:
					ItemStack item1 = offer.getItem1();
					assert !ItemUtils.isEmpty(item1);
					inventory.setItem(column + 9, item1);

					ItemStack item2 = offer.getItem2();
					if (!ItemUtils.isEmpty(item2)) {
						inventory.setItem(column + 18, item2);
					}
				}
				// fill in traded item:
				inventory.setItem(column, tradedItem);
			}

			// add the special buttons:
			this.setActionButtons(inventory);
			// show editing inventory:
			player.openInventory(inventory);
			return true;
		}

		@Override
		protected void onInventoryClick(InventoryClickEvent event, Player player) {
			TradingPlayerShopkeeper shopkeeper = this.getShopkeeper();
			event.setCancelled(true);
			int slot = event.getRawSlot();
			if (slot >= 0 && slot < TRADE_COLUMNS) {
				// handle changing sell stack size:
				this.handleUpdateItemAmountOnClick(event, 1);
			} else if ((slot >= 9 && slot <= 16) || (slot >= 18 && slot <= 25)) {
				if (shopkeeper.clickedItem != null) {
					// placing item:
					Inventory inventory = event.getInventory();
					Bukkit.getScheduler().runTask(ShopkeepersPlugin.getInstance(), () -> {
						inventory.setItem(slot, shopkeeper.clickedItem);
						shopkeeper.clickedItem = null;
					});
				} else {
					// changing stack size of clicked item:
					this.handleUpdateItemAmountOnClick(event, 0);
				}
			} else if (slot >= 27) {
				// clicking in player inventory:
				if (event.isShiftClick() || !event.isLeftClick()) {
					return;
				}
				ItemStack cursor = event.getCursor();
				if (!ItemUtils.isEmpty(cursor)) {
					return;
				}
				ItemStack current = event.getCurrentItem();
				if (!ItemUtils.isEmpty(current)) {
					shopkeeper.clickedItem = current.clone();
					shopkeeper.clickedItem.setAmount(1);
				}
			} else {
				super.onInventoryClick(event, player);
			}
		}

		@Override
		protected void saveEditor(Inventory inventory, Player player) {
			TradingPlayerShopkeeper shopkeeper = this.getShopkeeper();
			for (int column = 0; column < TRADE_COLUMNS; column++) {
				ItemStack resultItem = inventory.getItem(column);
				if (ItemUtils.isEmpty(resultItem)) continue; // not valid recipe column

				ItemStack cost1 = ItemUtils.getNullIfEmpty(inventory.getItem(column + 9));
				ItemStack cost2 = ItemUtils.getNullIfEmpty(inventory.getItem(column + 18));

				// handle cost2 item as cost1 item if there is no cost1 item:
				if (cost1 == null) {
					cost1 = cost2;
					cost2 = null;
				}

				// add or remove offer:
				if (cost1 != null) {
					shopkeeper.addOffer(resultItem, cost1, cost2);
				} else {
					shopkeeper.removeOffer(resultItem);
				}
			}
			shopkeeper.clickedItem = null;
		}
	}

	protected static class TradingPlayerShopTradingHandler extends PlayerShopTradingHandler {

		protected TradingPlayerShopTradingHandler(TradingPlayerShopkeeper shopkeeper) {
			super(shopkeeper);
		}

		@Override
		public TradingPlayerShopkeeper getShopkeeper() {
			return (TradingPlayerShopkeeper) super.getShopkeeper();
		}

		@Override
		protected boolean prepareTrade(TradeData tradeData) {
			if (!super.prepareTrade(tradeData)) return false;
			Player tradingPlayer = tradeData.tradingPlayer;
			TradingRecipe tradingRecipe = tradeData.tradingRecipe;

			assert chestInventory != null & newChestContents != null;

			// remove result items from chest contents:
			ItemStack resultItem = tradingRecipe.getResultItem();
			assert resultItem != null;
			if (ItemUtils.removeItems(newChestContents, resultItem) != 0) {
				this.debugPreventedTrade(tradingPlayer, "The shop's chest doesn't contain the required items.");
				return false;
			}

			// add traded items to chest contents:
			if (!this.addItems(newChestContents, tradingRecipe.getItem1(), tradeData.offeredItem1)
					|| !this.addItems(newChestContents, tradingRecipe.getItem2(), tradeData.offeredItem2)) {
				this.debugPreventedTrade(tradingPlayer, "The shop's chest cannot hold the traded items.");
				return false;
			}
			return true;
		}

		// The items the trading player gave might slightly differ from the required items,
		// but are still accepted for the trade, depending on minecraft's item comparison and settings.
		// Therefore we differ between require and offered items here.
		// Returns false, if not all items could be added to the contents:
		private boolean addItems(ItemStack[] contents, ItemStack requiredItem, ItemStack offeredItem) {
			if (ItemUtils.isEmpty(requiredItem)) return true;
			int amountAfterTaxes = this.getAmountAfterTaxes(requiredItem.getAmount());
			if (amountAfterTaxes > 0) {
				ItemStack receivedItem = offeredItem.clone(); // create a copy, just in case
				receivedItem.setAmount(amountAfterTaxes);
				if (ItemUtils.addItems(contents, receivedItem) != 0) {
					// couldn't add all items to the contents:
					return false;
				}
			}
			return true;
		}
	}

	// contains only one offer for a specific type of item:
	private final List<TradingOffer> offers = new ArrayList<>();
	private final List<TradingOffer> offersView = Collections.unmodifiableList(offers);

	// TODO conflicts if multiple players are editing at the same time
	// TODO maybe enforce only one editor at the same time? (currently shop owner and admins can edit at the same time)
	private ItemStack clickedItem;

	/**
	 * Creates a not yet initialized {@link TradingPlayerShopkeeper} (for use in sub-classes).
	 * <p>
	 * See {@link AbstractShopkeeper} for details on initialization.
	 * 
	 * @param id
	 *            the shopkeeper id
	 */
	protected TradingPlayerShopkeeper(int id) {
		super(id);
	}

	protected TradingPlayerShopkeeper(int id, PlayerShopCreationData shopCreationData) throws ShopkeeperCreateException {
		super(id);
		this.initOnCreation(shopCreationData);
	}

	protected TradingPlayerShopkeeper(int id, ConfigurationSection configSection) throws ShopkeeperCreateException {
		super(id);
		this.initOnLoad(configSection);
	}

	@Override
	protected void setup() {
		if (this.getUIHandler(DefaultUITypes.EDITOR()) == null) {
			this.registerUIHandler(new TradingPlayerShopEditorHandler(this));
		}
		if (this.getUIHandler(DefaultUITypes.TRADING()) == null) {
			this.registerUIHandler(new TradingPlayerShopTradingHandler(this));
		}
		super.setup();
	}

	@Override
	protected void loadFromSaveData(ConfigurationSection configSection) throws ShopkeeperCreateException {
		super.loadFromSaveData(configSection);
		// load offers:
		this._clearOffers();
		this._addOffers(TradingOffer.loadFromConfig(configSection, "offers"));
	}

	@Override
	public void save(ConfigurationSection configSection) {
		super.save(configSection);
		// save offers:
		TradingOffer.saveToConfig(configSection, "offers", this.getOffers());
	}

	@Override
	public TradingPlayerShopType getType() {
		return SKDefaultShopTypes.PLAYER_TRADING();
	}

	@Override
	public List<TradingRecipe> getTradingRecipes(Player player) {
		List<TradingRecipe> recipes = new ArrayList<>();
		List<ItemCount> chestItems = this.getItemsFromChest();
		for (TradingOffer offer : this.getOffers()) {
			ItemStack resultItem = offer.getResultItem();
			assert !ItemUtils.isEmpty(resultItem);
			ItemCount itemCount = ItemCount.findSimilar(chestItems, resultItem);
			if (itemCount == null) continue;

			int itemAmountInChest = itemCount.getAmount();
			if (itemAmountInChest >= resultItem.getAmount()) {
				recipes.add(offer); // TradingOffer extends TradingRecipe
			}
		}
		return Collections.unmodifiableList(recipes);
	}

	private List<ItemCount> getItemsFromChest() {
		return this.getItemsFromChest(null);
	}

	// OFFERS:

	public List<TradingOffer> getOffers() {
		return offersView;
	}

	public TradingOffer getOffer(ItemStack tradedItem) {
		for (TradingOffer offer : this.getOffers()) {
			if (ItemUtils.isSimilar(offer.getResultItem(), tradedItem)) {
				return offer;
			}
		}
		return null;
	}

	public TradingOffer addOffer(ItemStack resultItem, ItemStack item1, ItemStack item2) {
		// create offer (also handles validation):
		TradingOffer newOffer = new TradingOffer(resultItem, item1, item2);

		// add new offer (replacing any previous offer for the same item):
		this._addOffer(newOffer);
		this.markDirty();
		return newOffer;
	}

	private void _addOffer(TradingOffer offer) {
		assert offer != null;
		// remove previous offer for the same item:
		this.removeOffer(offer.getResultItem());
		offers.add(offer);
	}

	private void _addOffers(Collection<TradingOffer> offers) {
		assert offers != null;
		for (TradingOffer offer : offers) {
			if (offer == null) continue; // skip invalid entries
			// add new offer (replacing any previous offer for the same item):
			this._addOffer(offer);
		}
	}

	private void _clearOffers() {
		offers.clear();
	}

	public void clearOffers() {
		this._clearOffers();
		this.markDirty();
	}

	public void removeOffer(ItemStack tradedItem) {
		Iterator<TradingOffer> iterator = offers.iterator();
		while (iterator.hasNext()) {
			if (ItemUtils.isSimilar(iterator.next().getResultItem(), tradedItem)) {
				iterator.remove();
				this.markDirty();
				break;
			}
		}
	}
}
