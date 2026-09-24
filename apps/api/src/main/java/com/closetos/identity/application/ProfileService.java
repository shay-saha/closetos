package com.closetos.identity.application;

import com.closetos.identity.api.IdentityAccess;
import com.closetos.identity.api.ProfileAccess;
import com.closetos.identity.api.ProfileDetails;
import com.closetos.identity.api.ProfileUpdate;
import com.closetos.platform.api.DomainException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService implements ProfileAccess {
    private final IdentityAccess identity;
    private final JdbcClient jdbc;

    public ProfileService(IdentityAccess identity, JdbcClient jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ProfileDetails current() {
        return profile(identity.currentUserId());
    }

    @Transactional
    public ProfileDetails update(ProfileUpdate request) {
        UUID owner = identity.currentUserId();
        try {
            new Locale.Builder().setLanguageTag(request.locale()).build();
            Currency.getInstance(request.currency());
            ZoneId.of(request.timezone());
        } catch (IllformedLocaleException | DateTimeException exception) {
            throw DomainException.invalid("Choose a valid locale, currency and timezone.");
        } catch (IllegalArgumentException exception) {
            throw DomainException.invalid("Choose a valid currency.");
        }
        int updated =
                jdbc.sql(
                                """
                UPDATE user_profile SET display_name = :name, locale = :locale, currency = :currency,
                    timezone = :timezone, delete_original_after_isolation = :privacy, version = version + 1, updated_at = now()
                WHERE id = :owner AND version = :version
                """)
                        .param("name", request.displayName().strip())
                        .param("locale", request.locale())
                        .param("currency", request.currency())
                        .param("timezone", request.timezone())
                        .param("privacy", request.deleteOriginalAfterIsolation())
                        .param("owner", owner)
                        .param("version", request.version())
                        .update();
        if (updated != 1) throw DomainException.conflict();
        return profile(owner);
    }

    private ProfileDetails profile(UUID owner) {
        return jdbc.sql(
                        "SELECT id, display_name, locale, currency, timezone, delete_original_after_isolation, version FROM user_profile WHERE id = :owner")
                .param("owner", owner)
                .query(ProfileDetails.class)
                .single();
    }
}
