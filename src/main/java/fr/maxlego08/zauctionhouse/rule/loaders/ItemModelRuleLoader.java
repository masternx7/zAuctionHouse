package fr.maxlego08.zauctionhouse.rule.loaders;

import fr.maxlego08.zauctionhouse.api.rules.Rule;
import fr.maxlego08.zauctionhouse.api.rules.RuleConfigHelper;
import fr.maxlego08.zauctionhouse.api.rules.loader.RuleLoader;
import fr.maxlego08.zauctionhouse.rule.rules.ItemModelRule;

import java.util.List;
import java.util.Map;

public class ItemModelRuleLoader implements RuleLoader {

    @Override
    public String getType() {
        return "item-model";
    }

    @Override
    public Rule load(Map<?, ?> configuration) {
        List<String> models = RuleConfigHelper.getStringList(configuration, "models");
        if (models.isEmpty()) {
            String single = RuleConfigHelper.getString(configuration, "model");
            if (single != null) {
                models = List.of(single);
            }
        }
        if (models.isEmpty()) return null;
        return new ItemModelRule(models);
    }
}
