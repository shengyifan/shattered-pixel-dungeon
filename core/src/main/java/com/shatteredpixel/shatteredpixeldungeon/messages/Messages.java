/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.messages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.I18NBundle;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/*
	Simple wrapper class for libGDX I18NBundles.

	The core idea here is that each string resource's key is a combination of the class definition and a local value.
	An object or static method would usually call this with an object/class reference (usually its own) and a local key.
	This means that an object can just ask for "name" rather than, say, "items.weapon.enchantments.death.name"
 */
public class Messages {

	private static ArrayList<I18NBundle> bundles;
	private static Languages lang;
	private static Locale locale;
	private static final ThreadLocal<LanguageContext> scopedLanguage = new ThreadLocal<>();
	private static final ThreadLocal<EnumMap<Languages, LanguageContext>> scopedResources =
			ThreadLocal.withInitial(() -> new EnumMap<>(Languages.class));

	private static final class LanguageContext {
		final Languages language;
		final Locale locale;
		final ArrayList<I18NBundle> bundles;
		final HashMap<String, DecimalFormat> formatters = new HashMap<>();

		LanguageContext(Languages language) {
			this.language = language;
			locale = language == Languages.ENGLISH ? Locale.ENGLISH : new Locale(language.code());
			bundles = loadBundles(language);
		}
	}

	/**
	 * Read localized text on this thread without changing the game's selected language.
	 * Resources and number formatters are private to the calling thread and language.
	 * The scope is deliberately not inherited by threads or scheduled callbacks.
	 * Use only for pure presentation reads: gameplay itself has language-dependent rules.
	 */
	public static <T> T withLanguage(Languages language, Supplier<T> supplier) {
		Objects.requireNonNull(language, "language");
		Objects.requireNonNull(supplier, "supplier");
		LanguageContext previous = scopedLanguage.get();
		if (previous != null && previous.language == language) return supplier.get();
		LanguageContext context = scopedResources.get().computeIfAbsent(language, LanguageContext::new);
		scopedLanguage.set(context);
		try {
			return supplier.get();
		} finally {
			if (previous == null) scopedLanguage.remove();
			else scopedLanguage.set(previous);
		}
	}

	public static final String NO_TEXT_FOUND = "!!!NO TEXT FOUND!!!";

	public static Languages lang(){
		LanguageContext context = scopedLanguage.get();
		return context == null ? lang : context.language;
	}

	/** The application's selected language, independent of a temporary presentation scope. */
	public static Languages selectedLanguage() {
		return lang;
	}

	public static Locale locale(){
		LanguageContext context = scopedLanguage.get();
		return context == null ? locale : context.locale;
	}

	/**
	 * Setup Methods
	 */

	private static String[] prop_files = new String[]{
			Assets.Messages.ACTORS,
			Assets.Messages.ITEMS,
			Assets.Messages.JOURNAL,
			Assets.Messages.LEVELS,
			Assets.Messages.MISC,
			Assets.Messages.PLANTS,
			Assets.Messages.SCENES,
			Assets.Messages.UI,
			Assets.Messages.WINDOWS
	};

	static{
		formatters = new HashMap<>();
		setup(SPDSettings.language());
	}

	public static void setup( Languages lang ){
		//seeing as missing keys are part of our process, this is faster than throwing an exception
		I18NBundle.setExceptionOnMissingKey(false);

		//store language and locale info for various string logic
		Messages.lang = lang;
		if (lang == Languages.ENGLISH){
			locale = Locale.ENGLISH;
		} else {
			locale = new Locale(lang.code());
		}
		formatters.clear();
		bundles = loadBundles(lang);
	}

	private static ArrayList<I18NBundle> loadBundles(Languages language) {
		// English is the source bundle. This only reads assets; it never changes GUI settings.
		Locale bundleLocal = language == Languages.ENGLISH ? Locale.ROOT : new Locale(language.code());
		ArrayList<I18NBundle> loaded = new ArrayList<>();
		for (String file : prop_files) {
			if (bundleLocal.getLanguage().equals("id")){
				//This is a really silly hack to fix some platforms using "id" for indonesian and some using "in" (Android 14- mostly).
				//So if we detect "id" then we treat "###_in" as the base bundle so that it gets loaded instead of English.
				loaded.add(I18NBundle.createBundle(Gdx.files.internal(file + "_in"), bundleLocal));
			} else {
				loaded.add(I18NBundle.createBundle(Gdx.files.internal(file), bundleLocal));
			}
		}
		return loaded;
	}



	/**
	 * Resource grabbing methods
	 */

	public static String get(String key, Object...args){
		return get(null, key, args);
	}

	public static String get(Object o, String k, Object...args){
		return get(o.getClass(), k, args);
	}

	public static String get(Class c, String k, Object...args){
		String key;
		if (c != null){
			key = c.getName().replace("com.shatteredpixel.shatteredpixeldungeon.", "");
			key += "." + k;
		} else
			key = k;

		key = key.toLowerCase(Locale.ENGLISH);
		String value = getFromBundle(key);
		if (value != null){
			String rendered = args.length > 0 ? format(value, args) : value;
			return com.watabou.noosa.Game.observer.onTextResource(rendered, key, lang().code(), args, value);
		} else {
			//this is so child classes can inherit properties from their parents.
			//in cases where text is commonly grabbed as a utility from classes that aren't mean to be instantiated
			//(e.g. flavourbuff.dispTurns()) using .class directly is probably smarter to prevent unnecessary recursive calls.
			if (c != null && c.getSuperclass() != null){
				return get(c.getSuperclass(), k, args);
			} else {
				return NO_TEXT_FOUND;
			}
		}
	}

	private static String getFromBundle(String key){
		String result;
		LanguageContext context = scopedLanguage.get();
		for (I18NBundle b : context == null ? bundles : context.bundles){
			result = b.get(key);
			//if it isn't the return string for no key found, return it
			if (result.length() != key.length()+6 || !result.contains(key)){
				return result;
			}
		}
		return null;
	}



	/**
	 * String Utility Methods
	 */

	public static String format( String format, Object...args ) {
		String rendered;
		try {
			rendered = String.format(locale(), format, args);
		} catch (IllegalFormatException e) {
			ShatteredPixelDungeon.reportException( new Exception("formatting error for the string: " + format, e) );
			return com.watabou.noosa.Game.observer.onTextOperation("format_failed", format, format, args);
		}
		return com.watabou.noosa.Game.observer.onTextOperation("format", rendered, format, args);
	}

	private static HashMap<String, DecimalFormat> formatters;

	public static String decimalFormat( String format, double number ){
		LanguageContext context = scopedLanguage.get();
		HashMap<String, DecimalFormat> activeFormatters = context == null ? formatters : context.formatters;
		if (!activeFormatters.containsKey(format)){
			activeFormatters.put(format, new DecimalFormat(format, DecimalFormatSymbols.getInstance(locale())));
		}
		String rendered = activeFormatters.get(format).format(number);
		return com.watabou.noosa.Game.observer.onTextOperation("decimal_format", rendered, format, number);
	}

	public static String capitalize( String str ){
		String rendered = str.length() == 0 ? str : str.substring( 0, 1 ).toUpperCase(locale()) + str.substring( 1 );
		return com.watabou.noosa.Game.observer.onTextOperation("capitalize", rendered, str);
	}

	//Words which should not be capitalized in title case, mostly prepositions which appear ingame
	//This list is not comprehensive!
	private static final HashSet<String> noCaps = new HashSet<>(
			Arrays.asList("a", "an", "and", "of", "by", "to", "the", "x", "for")
	);

	public static String titleCase( String str ){
		//English capitalizes every word except for a few exceptions
		if (lang() == Languages.ENGLISH){
			String result = "";
			//split by any unicode space character
			for (String word : str.split("(?<=\\p{Zs})")){
				if (noCaps.contains(word.trim().toLowerCase(Locale.ENGLISH).replaceAll(":|[0-9]", ""))){
					result += word;
				} else {
					result += capitalize(word);
				}
			}
			//first character is always capitalized.
			return com.watabou.noosa.Game.observer.onTextOperation("title_case", capitalize(result), str);
		}

		//Otherwise, use sentence case
		return com.watabou.noosa.Game.observer.onTextOperation("title_case", capitalize(str), str);
	}

	public static String upperCase( String str ){
		return com.watabou.noosa.Game.observer.onTextOperation("upper_case", str.toUpperCase(locale()), str);
	}

	public static String lowerCase( String str ){
		return com.watabou.noosa.Game.observer.onTextOperation("lower_case", str.toLowerCase(locale()), str);
	}

	/** Explicit composition: each supplied object's original conversion occurs once. */
	public static String concat(Object left, Object right) {
		String first = String.valueOf(left), second = String.valueOf(right);
		return com.watabou.noosa.Game.observer.onTextOperation("concat", first + second,
				frozenConcatOperand(left, first), frozenConcatOperand(right, second));
	}

	private static Object frozenConcatOperand(Object original, String materialized) {
		return original == null || original instanceof Boolean || original instanceof Character
				|| original instanceof Byte || original instanceof Short || original instanceof Integer
				|| original instanceof Long || original instanceof Float || original instanceof Double ? original : materialized;
	}

	public static String literal(String text) {
		return com.watabou.noosa.Game.observer.onTextOperation("literal", text, text);
	}

	public static String userText(String text) {
		return com.watabou.noosa.Game.observer.onTextOperation("user", text, text);
	}

	public static String externalText(String text) {
		return com.watabou.noosa.Game.observer.onTextOperation("external", text, text);
	}

	/** Only for reviewed fixed code catalogs; existing resource/user origins are preserved. */
	public static String systemCatalogText(String text) {
		return com.watabou.noosa.Game.observer.onTextOperation("system_catalog", text, text);
	}

	/** A known non-localized prefix, never an arbitrary slice of a resource. */
	public static String stripPrefix(String text, String prefix) {
		return com.watabou.noosa.Game.observer.onTextOperation("strip_prefix", text.substring(prefix.length()), text, prefix);
	}

	public static String substring(String text, int begin) {
		return substring(text, begin, text.length());
	}

	public static String substring(String text, int begin, int end) {
		return com.watabou.noosa.Game.observer.onTextOperation("slice", text.substring(begin, end), text, begin, end);
	}

	public static String replace(String text, char oldValue, char newValue) {
		return com.watabou.noosa.Game.observer.onTextOperation("replace", text.replace(oldValue, newValue), text,
				String.valueOf(oldValue), String.valueOf(newValue));
	}

	public static String replace(String text, String oldValue, String newValue) {
		return com.watabou.noosa.Game.observer.onTextOperation("replace", text.replace(oldValue, newValue), text, oldValue, newValue);
	}

	public static String upperCase(String text, Locale locale) {
		return com.watabou.noosa.Game.observer.onTextOperation("upper_case", text.toUpperCase(locale), text);
	}

	public static String lowerCase(String text, Locale locale) {
		return com.watabou.noosa.Game.observer.onTextOperation("lower_case", text.toLowerCase(locale), text);
	}
}
