package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.Locale;

/** V74.2のSQL backfillでは扱えないUnicode NFKCを、実行時と同じ規則で既存leadへ適用する。 */
public class V74_3__crm_lead_search_key_nfkc extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (PreparedStatement select = context.getConnection().prepareStatement(
                "SELECT id, company_name, contact_email, contact_phone FROM t_lead");
             ResultSet rows = select.executeQuery();
             PreparedStatement update = context.getConnection().prepareStatement(
                     "UPDATE t_lead SET company_name_normalized = ?, "
                             + "contact_email_normalized = ?, contact_phone_normalized = ? WHERE id = ?")) {
            while (rows.next()) {
                update.setString(1, normalize(rows.getString("company_name"), true));
                update.setString(2, normalize(rows.getString("contact_email"), false));
                update.setString(3, normalize(rows.getString("contact_phone"), false));
                update.setLong(4, rows.getLong("id"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    static String normalize(String value, boolean company) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String key = company
                ? normalized.replaceAll("\\s+", "")
                : normalized.replaceAll("[^a-z0-9+@.]", "");
        return key.isEmpty() ? null : key;
    }
}
