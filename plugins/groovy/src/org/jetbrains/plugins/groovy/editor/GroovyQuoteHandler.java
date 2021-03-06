/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.editor;

import com.intellij.codeInsight.editorActions.MultiCharQuoteHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;

import static org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes.*;
import static org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes.mREGEX_BEGIN;
import static org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes.mREGEX_END;

/**
 * @author ven
 */
public class GroovyQuoteHandler implements MultiCharQuoteHandler {

  @Override
  public boolean isClosingQuote(HighlighterIterator iterator, int offset) {
    final IElementType tokenType = iterator.getTokenType();

    if (tokenType == mGSTRING_END) return true;
    if (tokenType == GroovyTokenTypes.mSTRING_LITERAL || tokenType == mGSTRING_LITERAL) {
      int start = iterator.getStart();
      int end = iterator.getEnd();
      return end - start >= 1 && offset == end - 1 ||
             end - start >= 5 && offset >= end - 3;
    }
    if (tokenType == mREGEX_END) {
      int start = iterator.getStart();
      int end = iterator.getEnd();
      return end - start >= 1 && offset == end - 1;
    }
    return false;
  }

  @Override
  public boolean isOpeningQuote(HighlighterIterator iterator, int offset) {
    final IElementType tokenType = iterator.getTokenType();

    if (tokenType == mGSTRING_BEGIN || tokenType == mREGEX_BEGIN) return true;
    if (tokenType == mGSTRING_LITERAL || tokenType == mSTRING_LITERAL) {
      int start = iterator.getStart();
      return offset == start;
    }
    return false;
  }

  @Override
  public boolean hasNonClosedLiteral(Editor editor, HighlighterIterator iterator, int offset) {
    final IElementType tokenType = iterator.getTokenType();
    if (tokenType == mSTRING_LITERAL || tokenType == mGSTRING_BEGIN || tokenType ==
                                                                                                         mGSTRING_LITERAL || tokenType ==
                                                                                                                                              mGSTRING_CONTENT) {
      final Document document = iterator.getDocument();
      if (document == null) return false;
      final String literal = document.getText().substring(iterator.getStart(), offset + 1);
      if ("'''".equals(literal) || "\"\"\"".equals(literal) || "'".equals(literal) || "\"".equals(literal)) {
        return true;
      }
    }

    return !(tokenType == mGSTRING_CONTENT || tokenType == mGSTRING_LITERAL || tokenType ==
                                                                                                                 mSTRING_LITERAL || tokenType ==
                                                                                                                                                     mGSTRING_END);
  }

  @Override
  public boolean isInsideLiteral(HighlighterIterator iterator) {
    final IElementType tokenType = iterator.getTokenType();
    return tokenType == mSTRING_LITERAL || tokenType == mGSTRING_LITERAL;
  }

  @Override
  public CharSequence getClosingQuote(HighlighterIterator iterator, int offset) {
    if (offset >= 3) {
      Document document = iterator.getDocument();
      if (document == null) return null;
      String quote = document.getText(new TextRange(offset - 3, offset));
      if ("'''".equals(quote)) return quote;
      if ("\"\"\"".equals(quote)) return quote;
    }
    if (offset >= 2) {
      Document document = iterator.getDocument();
      if (document == null) return null;
      String quote = document.getText(new TextRange(offset - 2, offset));
      if ("$/".equals(quote)) return "/$";
    }
    return null;
  }
}
