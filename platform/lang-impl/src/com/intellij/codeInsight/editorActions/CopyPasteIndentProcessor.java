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
package com.intellij.codeInsight.editorActions;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.CharFilter;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author yole
 */
public class CopyPasteIndentProcessor implements CopyPastePostProcessor<IndentTransferableData> {
  @Override
  public List<IndentTransferableData> collectTransferableData(PsiFile file,
                                                          Editor editor,
                                                          int[] startOffsets,
                                                          int[] endOffsets) {
    if (!acceptFileType(file.getFileType())) {
      return null;
    }
    return Collections.singletonList(new IndentTransferableData(editor.getCaretModel().getOffset()));
  }

  private static boolean acceptFileType(FileType fileType) {
    for(PreserveIndentOnPasteBean bean: Extensions.getExtensions(PreserveIndentOnPasteBean.EP_NAME)) {
      if (fileType.getName().equals(bean.fileType)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<IndentTransferableData> extractTransferableData(Transferable content) {
    IndentTransferableData indentData = new IndentTransferableData(-1);
    try {
      final DataFlavor flavor = IndentTransferableData.getDataFlavorStatic();
      if (flavor != null) {
        final Object transferData = content.getTransferData(flavor);
        if (transferData instanceof IndentTransferableData) {
          indentData = (IndentTransferableData)transferData;
        }
      }
    }
    catch (UnsupportedFlavorException e) {
      // do nothing
    }
    catch (IOException e) {
      // do nothing
    }
    return Collections.singletonList(indentData);
  }

  @Override
  public void processTransferableData(final Project project,
                                      final Editor editor,
                                      final RangeMarker bounds,
                                      final int caretOffset,
                                      final Ref<Boolean> indented,
                                      final List<IndentTransferableData> values) {
    if (!CodeInsightSettings.getInstance().INDENT_TO_CARET_ON_PASTE) {
      return;
    }
    assert values.size() == 1;
    if (values.get(0).getOffset() == caretOffset) return;

    final Document document = editor.getDocument();
    final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    if (psiFile == null || !acceptFileType(psiFile.getFileType())) {
      return;
    }
    //System.out.println("--- before indent ---\n" + document.getText());
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        final boolean useTabs =
          CodeStyleSettingsManager.getSettings(project).useTabCharacter(psiFile.getFileType());
        CharFilter NOT_INDENT_FILTER = new CharFilter() {
          @Override
          public boolean accept(char ch) {
            return useTabs? ch != '\t' : ch != ' ';
          }
        };
        String pastedText = document.getText(TextRange.create(bounds));

        int startLine = document.getLineNumber(bounds.getStartOffset());
        int endLine = document.getLineNumber(bounds.getEndOffset());

        //calculate from indent
        int fromIndent = StringUtil.findFirst(pastedText, NOT_INDENT_FILTER);
        if (fromIndent < 0) fromIndent = 0;

        //calculate to indent
        String initialText = document.getText(TextRange.create(0, bounds.getStartOffset())) +
                   document.getText(TextRange.create(bounds.getEndOffset(), document.getTextLength()));
        int toIndent = 0;
        if (initialText.length() > 0) {
          final DocumentImpl initialDocument = new DocumentImpl(initialText);
          int lineNumber = initialDocument.getTextLength() > caretOffset? initialDocument.getLineNumber(caretOffset)
                                                                        : initialDocument.getLineCount() - 1;
          final int offset = getLineStartSafeOffset(initialDocument, lineNumber);

          if (bounds.getStartOffset() == offset) {
            String toString = initialDocument.getText(TextRange.create(offset, initialDocument.getLineEndOffset(lineNumber)));
            toIndent = StringUtil.findFirst(toString, NOT_INDENT_FILTER);
            if (toIndent < 0 && StringUtil.isEmptyOrSpaces(toString)) {
              toIndent = toString.length();
            }
            else if ((toIndent < 0 || toString.startsWith("\n")) && initialText.length() >= caretOffset) {
              toIndent = caretOffset - offset;
            }
          }
          else if (isNotApplicable(initialDocument, offset))
            return;
          else {                       // selection
            startLine += 1;
            toIndent = Math.abs(bounds.getStartOffset() - offset);
          }
        }

        // actual difference in indentation level
        int indent = toIndent - fromIndent;
        if (useTabs)       // indent is counted in tab units
          indent *=
            CodeStyleSettingsManager.getSettings(project).getTabSize(psiFile.getFileType());
        // don't indent single-line text
        if (!StringUtil.startsWithWhitespace(pastedText) && !StringUtil.endsWithLineBreak(pastedText) &&
             !(StringUtil.splitByLines(pastedText).length > 1))
          return;

        if (pastedText.endsWith("\n")) endLine -= 1;

        for (int i = startLine; i <= endLine; i++) {
          EditorActionUtil.indentLine(project, editor, i, indent);
        }
        indented.set(Boolean.TRUE);
      }

      private boolean isNotApplicable(DocumentImpl initialDocument, int offset) {
        return caretOffset < initialDocument.getTextLength() && !StringUtil
          .isEmptyOrSpaces(initialDocument.getText(TextRange.create(offset, caretOffset)));
      }
    });
    //System.out.println("--- after indent ---\n" + document.getText());
  }

  private static int getLineStartSafeOffset(final Document document, int line) {
    if (line >= document.getLineCount()) return document.getTextLength();
    return document.getLineStartOffset(line);
  }

}
