/*
 * 07/21/2009
 *
 * SpellingParser.java - A spell-checker for RSyntaxTextArea.
 * Copyright (C) 2009 Robert Futrell
 * robert_futrell at users.sourceforge.net
 * http://fifesoft.com/rsyntaxtextarea
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307 USA.
 */
package org.fife.ui.rsyntaxtextarea.spell;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.zip.ZipFile;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Element;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.focusabletip.FocusableTip;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.ExtendedHyperlinkListener;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;

import com.swabunga.spell.engine.Configuration;
import com.swabunga.spell.engine.SpellDictionary;
import com.swabunga.spell.engine.SpellDictionaryHashMap;
import com.swabunga.spell.engine.Word;
import com.swabunga.spell.event.DocumentWordTokenizer;
import com.swabunga.spell.event.SpellCheckEvent;
import com.swabunga.spell.event.SpellCheckListener;
import com.swabunga.spell.event.SpellChecker;
import com.swabunga.spell.event.StringWordTokenizer;


/**
 * A parser that spell-checks documents.  The spelling engine is a lightly
 * modified version of <a href="http://jazzy.sourceforge.net/">Jazzy</a>.
 * All Jazzy source, modified or otherwise, is licensed under the LGPL. just
 * like RSyntaxTextArea.<p>
 *
 * For source code only comments are spell checked.  For plain text files,
 * the entire content is spell checked.<p>
 *
 * This parser can be shared among multiple <code>RSyntaxTextArea</code>
 * instances.<p>
 *
 * Usage:
 * <pre>
 * RSyntaxTextArea textArea = new RSyntaxTextArea(40, 25);
 * File englishZip = new File("english_dic.zip");
 * SpellingParser parser = SpellingParser.createEnglishSpellingParser(englishZip, true);
 * textArea.addParser(parser);
 * </pre>
 *
 * @author Robert Futrell
 * @version 0.5
 */
public class SpellingParser extends AbstractParser
			implements SpellCheckListener, ExtendedHyperlinkListener {

	private DefaultParseResult result;
	private SpellChecker sc;
	private RSyntaxDocument doc;
	private int startOffs;
	private int errorCount;
	private int maxErrorCount;
	private Color squiggleUnderlineColor;
	private String noticePrefix;
	private String noticeSuffix;

	/**
	 * The "user dictionary."  If this is non-<code>null</code>, then the
	 * user will be able to select "Add word to dictionary" for spelling
	 * errors.  When this option is selected, the word is added to this
	 * file.
	 */
	private File dictionaryFile;

	private static final String MSG = "org.fife.ui.rsyntaxtextarea.spell.SpellingParser";
	private static final ResourceBundle msg = ResourceBundle.getBundle(MSG);

	private static final String ADD			= "add";
	private static final String REPLACE		= "replace";
	private static final String TOOLTIP_TEXT_FORMAT =
		"<html><img src='lightbulb.png' width='16' height='16'>{0}<hr><img src='spellcheck.png' width='16' height='16'>{1}<br>{2}<br>&nbsp;";

	/**
	 * The default maximum number of spelling errors to report for a document.
	 */
	private static final int DEFAULT_MAX_ERROR_COUNT			= 100;


	/**
	 * Constructor.
	 *
	 * @param dict The dictionary to use.
	 */
	public SpellingParser(SpellDictionary dict) {

		result = new DefaultParseResult(this);
		sc = new SpellChecker(dict);
		sc.addSpellCheckListener(this);
		setSquiggleUnderlineColor(Color.BLUE);
		setHyperlinkListener(this);
		setMaxErrorCount(DEFAULT_MAX_ERROR_COUNT);

		// Since the spelling callback can possibly be called many times
		// per parsing, we're extremely cheap here and pre-split our message
		// format instead of using MessageFormat.
		String temp = msg.getString("IncorrectSpelling");
		int offs = temp.indexOf("{0}");
		noticePrefix = temp.substring(0, offs);
		noticeSuffix = temp.substring(offs+3);

	}


	/**
	 * A utility method to easily create a parser for American or British
	 * English.
	 *
	 * @param zip The location of the <code>english_dic.zip</code> file
	 *        distributed with the spell checker add-on.
	 * @param american Whether the parser should be for American (as opposed
	 *        to British) English.
	 * @return The parser.
	 * @throws IOException If an error occurs reading the zip file.
	 */
	public static SpellingParser createEnglishSpellingParser(File zip,
									boolean american) throws IOException {

		long start = System.currentTimeMillis();

		SpellDictionaryHashMap dict = null;

		ZipFile zf = new ZipFile(zip);

		try {

			// Words common to American and British English
			InputStream in = zf.getInputStream(zf.getEntry("eng_com.dic"));
			BufferedReader r = new BufferedReader(new InputStreamReader(in));
			try {
				dict = new SpellDictionaryHashMap(r);
			} finally {
				r.close();
			}

			String[] others = null;
			if (american) {
				others = new String[] { "color", "labeled", "center", "ize",
										"yze" };
			}
			else { // British
				others = new String[] { "colour", "labelled", "centre",
										"ise", "yse" };
			}

			// Load words specific to the English dialect.
			for (int i=0; i<others.length; i++) {
				in = zf.getInputStream(zf.getEntry(others[i] + ".dic"));
				r = new BufferedReader(new InputStreamReader(in));
				try {
					dict.addDictionary(r);
				} finally {
					r.close();
				}
			}

		} finally {
			zf.close();
		}

		float secs = (System.currentTimeMillis() - start)/1000f;
		System.out.println("Loading dictionary took " + secs + " seconds");

		return new SpellingParser(dict);

	}


	/**
	 * Overridden to return the image base for {@link FocusableTip}s made
	 * from this parser's notices.
	 *
	 * @return The image base.
	 */
	public URL getImageBase() {
		return getClass().getResource("/org/fife/ui/rsyntaxtextarea/spell/");
	}


	private final int getLineOfOffset(int offs) {
		return doc.getDefaultRootElement().getElementIndex(offs);
	}


	/**
	 * Returns the maximum number of errors this parser will report for a single document.
	 *
	 * @return The maximum number of errors that will be reported.
	 * @see #setMaxErrorCount(int)
	 */
	public int getMaxErrorCount() {
		return maxErrorCount;
	}


	/**
	 * Returns the color to use when painting spelling errors in an editor.
	 *
	 * @return The color to use.
	 * @see #setSquiggleUnderlineColor(Color)
	 */
	public Color getSquiggleUnderlineColor() {
		return squiggleUnderlineColor;
	}


	/**
	 * Returns the user's dictionary file.
	 *
	 * @return The user's dictionary file, or <code>null</code> if none has
	 *         been set.
	 * @see #setUserDictionary(File)
	 */
	public File getUserDictionary() {
		return dictionaryFile;
	}


	/**
	 * {@inheritDoc}
	 */
	public void linkClicked(RSyntaxTextArea textArea, HyperlinkEvent e) {

		if (e.getEventType()==HyperlinkEvent.EventType.ACTIVATED) {

			String desc = e.getDescription();
			int temp = desc.indexOf("://");
			String operation = desc.substring(0, temp);
			String[] tokens = desc.substring(temp + 3).split(",");

			if (REPLACE.equals(operation)) {
				int offs = Integer.parseInt(tokens[0]);
				int len = Integer.parseInt(tokens[1]);
				String replacement = tokens[2];
				textArea.replaceRange(replacement, offs, offs+len);
				textArea.setSelectionStart(offs);
				textArea.setSelectionEnd(offs + replacement.length());
			}
			else if (ADD.equals(operation)) {
				if (dictionaryFile==null) {
					// TODO: Add callback for application to prompt to create
					// a user dictionary
					UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				}
				String word = tokens[0];
				if (sc.addToDictionary(word)) {
					for (int i=0; i<textArea.getParserCount(); i++) {
						// Should be in the list somewhere...
						if (textArea.getParser(i)==this) {
							textArea.forceReparsing(i);
							break;
						}
					}
				}
				else { // IO error adding the word
					UIManager.getLookAndFeel().provideErrorFeedback(textArea);
				}
			}

		}

	}


	/**
	 * {@inheritDoc}
	 */
	public ParseResult parse(RSyntaxDocument doc, String style) {

		long startTime = System.currentTimeMillis();

		Element root = doc.getDefaultRootElement();
		int lineCount = root.getElementCount();
		result.clearNotices();
		// Always spell check all lines, for now.
		result.setParsedLines(0, lineCount-1);
		sc.reset();
		this.doc = doc;
		errorCount = 0;

		// Use a faster method for spell-checking plain text.
		if (style==null || SyntaxConstants.SYNTAX_STYLE_NONE.equals(style)) {
			startOffs = 0;
			parseEntireDocument(doc);
		}

		else {

			outer:
			for (int line=0; line<lineCount; line++) {

				Token t = doc.getTokenListForLine(line);
				while (t!=null && t.isPaintable()) {
					if (t.type==Token.COMMENT_EOL ||
							t.type==Token.COMMENT_MULTILINE ||
							t.type==Token.COMMENT_DOCUMENTATION) {
						startOffs = t.offset;
						// TODO: Create a wordTokenizer that uses char[] array
						// to prevent String allocation.
						StringWordTokenizer swt =
									new StringWordTokenizer(t.getLexeme());
						int rc = sc.checkSpelling(swt);
						if (rc==SpellChecker.SPELLCHECK_CANCEL) {
							break outer; // Stop spell checking comments
						}
					}
					t = t.getNextToken();
				}

			}

		}

		float secs = (System.currentTimeMillis() - startTime)/1000f;
		System.out.println("Spell check completed in: " + secs + " seconds");
		System.out.println("Error count==" + errorCount);
		return result;

	}


	/**
	 * Spell-checks a plain text document.
	 *
	 * @param doc The document to spell check.
	 */
	private void parseEntireDocument(RSyntaxDocument doc) {
		DocumentWordTokenizer dwt = new DocumentWordTokenizer(doc);
		sc.checkSpelling(dwt);
	}


	/**
	 * Sets the maximum number of spelling errors this parser will report for a single
	 * text file.  Note that the file should be re-parsed after changing this value.
	 *
	 * @param max The ew maximum error count.
	 * @see #getMaxErrorCount()
	 */
	public void setMaxErrorCount(int max) {
		maxErrorCount = max;
	}


	/**
	 * Sets the color to use when painting spelling errors in an editor.
	 *
	 * @param color The color to use.
	 * @see #getSquiggleUnderlineColor()
	 */
	public void setSquiggleUnderlineColor(Color color) {
		squiggleUnderlineColor = color;
	}


	/**
	 * Sets the "user dictionary," that is, the dictionary that words can be
	 * added to at runtime.<p>
	 *
	 * If this is non-<code>null</code>, then on the focusable tool tip for
	 * spelling errors, there will be an option available: "Add word to
	 * dictionary."  If this is clicked then the "error" word is added to the
	 * user's dictionary and the document is re-parsed.
	 *
	 * @param dictionaryFile The dictionary file.  If this is <code>null</code>
	 *        then the user will not be able to add words.
	 * @throws IOException If an IO error occurs.
	 * @see #getUserDictionary()
	 */
	public void setUserDictionary(File dictionaryFile) throws IOException {
		SpellDictionaryHashMap userDict = null;
		if (dictionaryFile!=null) {
			if (!dictionaryFile.exists()) {
				// The file must exist for Jazzy to be happy
				FileWriter w = new FileWriter(dictionaryFile);
				w.close();
			}
			userDict = new SpellDictionaryHashMap(dictionaryFile);
		}
		else {
			// Unfortunately cannot use null, Jazzy won't allow it
			userDict = new SpellDictionaryHashMap();
		}
		sc.setUserDictionary(userDict);
		this.dictionaryFile = dictionaryFile;
	}


	/**
	 * Callback called when a spelling error is found.
	 *
	 * @param e The event.
	 */
	public void spellingError(SpellCheckEvent e) {
//		e.ignoreWord(true);
		String word = e.getInvalidWord();
		int offs = startOffs + e.getWordContextPosition();
		int line = getLineOfOffset(offs);
		String text = noticePrefix + word + noticeSuffix;
		SpellingParserNotice notice =
			new SpellingParserNotice(this, text, line, offs, word, sc);
		notice.setColor(getSquiggleUnderlineColor());
		result.addNotice(notice);
		if (++errorCount>=maxErrorCount) {
			System.out.println("Cancelling the spelling check!");
			e.cancel();
		}
	}


	/**
	 * The notice type returned by this parser.
	 */
	private static class SpellingParserNotice extends DefaultParserNotice {

		private String word;
		private SpellChecker sc;

		public SpellingParserNotice(SpellingParser parser, String msg,
									int line, int offs, String word,
									SpellChecker sc) {
			super(parser, msg, line, offs, word.length());
			this.word = word;
			this.sc = sc;
		}

		public String getToolTipText() {

			StringBuffer sb = new StringBuffer();
			String spacing = "&nbsp;&nbsp;&nbsp;";
			int threshold = sc.getConfiguration().getInteger(Configuration.SPELL_THRESHOLD);
			List suggestions = sc.getSuggestions(word, threshold);
			if (suggestions==null || suggestions.size()==0) {
				sb.append(spacing).append("<em>");
				sb.append(msg.getString("None"));
				sb.append("</em>");
			}
			else {
				sb.append("<center>");
				sb.append("<table width='75%'>");
				for (int i=0; i<suggestions.size(); i++) {
					if ((i%2)==0) {
						sb.append("<tr>");
					}
					sb.append("<td>&#8226;&nbsp;");
					Word suggestion = (Word)suggestions.get(i);
					sb.append("<a href='").append(REPLACE).append("://").
					append(getOffset()).append(',').
					append(getLength()).append(',').
					append(suggestion.getWord()).
					append("'>").
					append(suggestion.getWord()).
					append("</a>").
					append("</td>");
					if ((i&1)==1) {
						sb.append("</tr>");
					}
				}
				if ((suggestions.size()%2)==0) {
					sb.append("<td></td></tr>");
				}
				sb.append("</table>");
				sb.append("</center>");
				sb.append("<img src='add.png' width='16' height='16'>&nbsp;").
					append("<a href='").append(ADD).
					append("://").append(word).append("'>").
					append(msg.getString("ErrorToolTip.AddToDictionary")).
					append("</a>");

			}

			String firstLine = MessageFormat.format(
									msg.getString("ErrorToolTip.DescHtml"),
									new String[] { word });
			String temp = MessageFormat.format(TOOLTIP_TEXT_FORMAT,
							new String[] {
								firstLine,
								msg.getString("ErrorToolTip.SuggestionsHtml"),
								sb.toString() });

			return temp;

		}

	}


}