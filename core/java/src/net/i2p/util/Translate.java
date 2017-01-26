package net.i2p.util;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import gnu.gettext.GettextResource;

import net.i2p.I2PAppContext;
import net.i2p.util.ConcurrentHashSet;

/**
 * Translate strings efficiently.
 * We don't include an English or default ResourceBundle, we simply check
 * for "en" and return the original string.
 * Support real-time language changing with the routerconsole.lang
 * and routerconsole.country properties.
 *
 * To change language in router context, set the context properties PROP_LANG and PROP_COUNTRY.
 * To change language in app context, set the System properties PROP_LANG and PROP_COUNTRY.
 *
 * @author zzz, from a base generated by eclipse.
 * @since 0.7.9
 */
public abstract class Translate {
    public static final String PROP_LANG = "routerconsole.lang";
    /** @since 0.9.10 */
    public static final String PROP_COUNTRY = "routerconsole.country";
    /** non-null, two- or three-letter lower case, may be "" */
    private static final String _localeLang = Locale.getDefault().getLanguage();
    /** non-null, two-letter upper case, may be "" */
    private static final String _localeCountry = Locale.getDefault().getCountry();
    private static final Map<String, ResourceBundle> _bundles = new ConcurrentHashMap<String, ResourceBundle>(16);
    private static final Set<String> _missing = new ConcurrentHashSet<String>(16);
    /** use to look for untagged strings */
    private static final String TEST_LANG = "xx";
    private static final String TEST_STRING = "XXXX";

    /** lang in routerconsole.lang property, else current locale */
    public static String getString(String key, I2PAppContext ctx, String bun) {
        String lang = getLanguage(ctx);
        if (lang.equals("en"))
            return key;
        else if (lang.equals(TEST_LANG))
            return TEST_STRING;
        // shouldnt happen but dont dump the po headers if it does
        if (key.equals(""))
            return key;
        ResourceBundle bundle = findBundle(bun, lang, getCountry(ctx));
        if (bundle == null)
            return key;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public static String getString(String s, Object o, I2PAppContext ctx, String bun) {
        return getString(s, ctx, bun, o);
    }

    /** for {0} and {1} */
    public static String getString(String s, Object o, Object o2, I2PAppContext ctx, String bun) {
        return getString(s, ctx, bun, o, o2);
    }

    /**
     *  Varargs
     *  @param oArray parameters
     *  @since 0.9.8
     */
    public static String getString(String s, I2PAppContext ctx, String bun, Object... oArray) {
        String lang = getLanguage(ctx);
        if (lang.equals(TEST_LANG))
            return TEST_STRING + Arrays.toString(oArray) + TEST_STRING;
        String x = getString(s, ctx, bun);
        try {
            MessageFormat fmt = new MessageFormat(x, new Locale(lang));
            return fmt.format(oArray, new StringBuffer(), null).toString();
        } catch (IllegalArgumentException iae) {
            System.err.println("Bad format: orig: \"" + s +
                               "\" trans: \"" + x +
                               "\" params: " + Arrays.toString(oArray) +
                               " lang: " + lang);
            return "FIXME: " + x + ' ' + Arrays.toString(oArray);
        }
    }

    /**
     *  Use GNU ngettext
     *  For .po file format see http://www.gnu.org/software/gettext/manual/gettext.html.gz#Translating-plural-forms
     *
     *  @param n how many
     *  @param s singluar string, optionally with {0} e.g. "one tunnel"
     *  @param p plural string optionally with {0} e.g. "{0} tunnels"
     *  @since 0.7.14
     */
    public static String getString(int n, String s, String p, I2PAppContext ctx, String bun) {
        String lang = getLanguage(ctx);
        if (lang.equals(TEST_LANG))
            return TEST_STRING + '(' + n + ')' + TEST_STRING;
        ResourceBundle bundle = null;
        if (!lang.equals("en"))
            bundle = findBundle(bun, lang, getCountry(ctx));
        String x;
        if (bundle == null)
            x = n == 1 ? s : p;
        else
            x = GettextResource.ngettext(bundle, s, p, n);
        Object[] oArray = new Object[1];
        oArray[0] = Integer.valueOf(n);
        try {
            MessageFormat fmt = new MessageFormat(x, new Locale(lang));
            return fmt.format(oArray, new StringBuffer(), null).toString();
        } catch (IllegalArgumentException iae) {
            System.err.println("Bad format: sing: \"" + s +
                           "\" plural: \"" + p +
                           "\" lang: " + lang);
            return "FIXME: " + s + ' ' + p + ',' + n;
        }
    }

    /**
     *  Two- or three-letter lower case
     *  @return lang in routerconsole.lang property, else current locale
     */
    public static String getLanguage(I2PAppContext ctx) {
        String lang = ctx.getProperty(PROP_LANG);
        if (lang == null || lang.length() <= 0)
            lang = _localeLang;
        return lang;
    }

    /**
     *  Two-letter upper case or ""
     *  @return country in routerconsole.country property, else current locale
     *  @since 0.9.10
     */
    public static String getCountry(I2PAppContext ctx) {
        // property may be empty so we don't have a non-default
        // language and a default country
        return ctx.getProperty(PROP_COUNTRY, _localeCountry);
    }

    /**
     *  Only for use by standalone apps in App Context.
     *  NOT for use in Router Context.
     *  Does not persist, apps must implement their own persistence.
     *  Does NOT override context properties.
     *
     *  @param lang Two- or three-letter lower case, or null for default
     *  @param country Two-letter upper case, or null for default, or "" for none
     *  @since 0.9.27
     */
    public static void setLanguage(String lang, String country) {
        if (lang != null)
            System.setProperty(PROP_LANG, lang);
        else
            System.clearProperty(PROP_LANG);
        if (country != null)
            System.setProperty(PROP_COUNTRY, country);
        else
            System.clearProperty(PROP_COUNTRY);
    }

    /**
     * cache both found and not found for speed
     * @param lang non-null, if "" returns null
     * @param country non-null, may be ""
     * @return null if not found
     */
    private static ResourceBundle findBundle(String bun, String lang, String country) {
        String key = bun + '-' + lang + '-' + country;
        ResourceBundle rv = _bundles.get(key);
        if (rv == null && !_missing.contains(key)) {
            if ("".equals(lang)) {
                _missing.add(key);
                return null;
            }
            try {
                Locale loc;
                if ("".equals(country))
                    loc = new Locale(lang);
                else
                    loc = new Locale(lang, country);
                // We must specify the class loader so that a webapp can find the bundle in the .war
                rv = ResourceBundle.getBundle(bun, loc, Thread.currentThread().getContextClassLoader());
                if (rv != null)
                    _bundles.put(key, rv);
            } catch (MissingResourceException e) {
                _missing.add(key);
            }
        }
        return rv;
    }

    /**
     *  Return the "display language", e.g. "English" for the language specified
     *  by langCode, using the current language.
     *  Uses translation if available, then JVM Locale.getDisplayLanguage() if available, else default param.
     *
     *  @param langCode two- or three-letter lower-case
     *  @param dflt e.g. "English"
     *  @since 0.9.5
     */
    public static String getDisplayLanguage(String langCode, String dflt, I2PAppContext ctx, String bun) {
        String curLang = getLanguage(ctx);
        if (!"en".equals(curLang)) {
            String rv = getString(dflt, ctx, bun);
            if (!rv.equals(dflt))
                return rv;
            Locale curLocale = new Locale(curLang);
            rv = (new Locale(langCode)).getDisplayLanguage(curLocale);
            if (rv.length() > 0 && !rv.equals(langCode))
                return rv;
        }
        return dflt;
    }

    /**
     *  Clear the cache.
     *  Call this after adding new bundles to the classpath.
     *  @since 0.7.12
     */
    public static void clearCache() {
        _missing.clear();
        _bundles.clear();
        ResourceBundle.clearCache();
    }
}
