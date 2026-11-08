package fr.maxlego08.zauctionhouse.rule.rules;

import fr.maxlego08.zauctionhouse.api.rules.ItemRuleContext;
import fr.maxlego08.zauctionhouse.api.rules.Rule;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ItemModelRule implements Rule {

    private final List<String> exactModels;
    private final List<Pattern> patterns;

    public ItemModelRule(List<String> models) {
        this.exactModels = models.stream().filter(id -> !id.contains("*")).map(id -> id.toLowerCase(Locale.ROOT)).toList();
        this.patterns = models.stream().filter(id -> id.contains("*")).map(this::wildcardToPattern).toList();
    }

    private Pattern wildcardToPattern(String wildcard) {
        String regex = wildcard.toLowerCase(Locale.ROOT).replace(".", "\\.").replace("*", ".*");
        return Pattern.compile("^" + regex + "$");
    }

    @Override
    public boolean matches(ItemRuleContext context) {
        if (!context.getItemStack().hasItemMeta()) return false;

        ItemMeta meta = context.getItemStack().getItemMeta();
        if (meta == null) return false;

        NamespacedKey itemModel = meta.getItemModel();
        if (itemModel == null) return false;

        String model = itemModel.toString().toLowerCase(Locale.ROOT);

        if (exactModels.contains(model)) return true;

        for (Pattern pattern : patterns) {
            if (pattern.matcher(model).matches()) return true;
        }

        return false;
    }

    @Override
    public boolean isValid() {
        return !this.exactModels.isEmpty() || !this.patterns.isEmpty();
    }
}
