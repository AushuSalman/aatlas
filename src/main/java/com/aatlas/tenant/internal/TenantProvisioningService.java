package com.aatlas.tenant.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.tenant.CountryCode;
import com.aatlas.tenant.TenantProvisioning;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates companies. The only writer of {@code tenants} apart from {@link TenantService}.
 *
 * <p>{@link Propagation#MANDATORY} is the point of interest: provisioning must never run
 * on its own. Signup creates a company and its first user together, and a tenant with no
 * user is an orphan nobody can sign in to. Requiring a caller's transaction makes that a
 * startup-visible programming error rather than a row discovered months later.
 *
 * <p>Writes the {@code tenant_settings} row in the same breath, so "a tenant without
 * settings" is not a state that exists.
 */
@Service
class TenantProvisioningService implements TenantProvisioning {

    /** Shown when the signup form leaves Company blank, matching the frontend's default. */
    private static final String DEFAULT_COMPANY_NAME = "Northwind Industrial";

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_SLUG_LENGTH = 60;
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Suffix alphabet for slug collisions. No vowels, so a random suffix cannot spell. */
    private static final char[] SUFFIX = "bcdfghjkmnpqrstvwxz23456789".toCharArray();
    private static final int SUFFIX_LENGTH = 6;
    private static final int MAX_SLUG_ATTEMPTS = 5;

    private final TenantRepository tenants;
    private final TenantSettingsRepository settings;
    private final SecureRandom random = new SecureRandom();

    TenantProvisioningService(TenantRepository tenants, TenantSettingsRepository settings) {
        this.tenants = tenants;
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantView provision(NewTenant command) {
        String name = cleanName(command.name());
        CountryCode country = command.country();

        TenantEntity saved = tenants.save(new TenantEntity(name, uniqueSlug(name), country));
        settings.save(new TenantSettingsEntity(saved.getId(), country, country.tradingCurrency()));
        return new TenantView(
                saved.getId(), saved.getName(), saved.getSlug(), saved.getCountry(), saved.getTradingCurrency());
    }

    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return DEFAULT_COMPANY_NAME;
        }
        // Collapse internal runs of whitespace: "Kestrel   Supply" and "Kestrel Supply"
        // are the same company, and only one of them should appear in the workspace header.
        name = name.replaceAll("\\s+", " ");
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH).strip() : name;
    }

    /**
     * A URL-safe, unique slug.
     *
     * <p>Two companies genuinely can share a name, so a collision is expected rather than
     * exceptional and gets a random suffix instead of an error. The check is advisory -
     * {@code tenants_slug_uk} is what actually guarantees uniqueness under concurrency -
     * so this loop only has to make a collision unlikely, not impossible.
     */
    private String uniqueSlug(String name) {
        String base = slugify(name);
        if (base.isEmpty()) {
            // A name of pure punctuation or unsupported script. Rare enough to reject
            // loudly rather than paper over with a meaningless generated slug.
            throw ApiException.badRequest(
                    "company_name_unusable",
                    "That company name has no letters or digits we can use. Try a plainer spelling.");
        }
        if (!tenants.existsBySlug(base)) {
            return base;
        }
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String candidate = base + "-" + randomSuffix();
            if (!tenants.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // Five collisions on a 27^6 space means something is wrong with the generator,
        // not with this name. Fail rather than loop.
        throw new IllegalStateException("Could not derive a unique slug for company name: " + name);
    }

    private static String slugify(String name) {
        // Decompose accents, drop the combining marks: "Kestrel Ströme" -> "kestrel-strome".
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKD);
        folded = COMBINING_MARKS.matcher(folded).replaceAll("");
        String slug = NON_SLUG.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = trimDashes(slug);
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = trimDashes(slug.substring(0, MAX_SLUG_LENGTH));
        }
        return slug;
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX[random.nextInt(SUFFIX.length)]);
        }
        return suffix.toString();
    }
}
