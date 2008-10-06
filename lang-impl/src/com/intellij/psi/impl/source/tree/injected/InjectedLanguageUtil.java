package com.intellij.psi.impl.source.tree.injected;

import com.intellij.injected.editor.*;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.ParameterizedCachedValueImpl;
import com.intellij.psi.impl.PsiDocumentManagerImpl;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.*;
import com.intellij.util.ArrayUtil;
import com.intellij.util.SmartList;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author cdr
 */
public class InjectedLanguageUtil {
  private static final Key<ParameterizedCachedValue<Places, PsiElement>> INJECTED_PSI_KEY = Key.create("INJECTED_PSI");
  private static final Key<List<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>>> HIGHLIGHT_TOKENS = Key.create("HIGHLIGHT_TOKENS");

  public static void forceInjectionOnElement(@NotNull final PsiElement host) {
    enumerate(host, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
      public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
      }
    });
  }

  @Nullable
  public static List<Pair<PsiElement, TextRange>> getInjectedPsiFiles(@NotNull final PsiElement host) {
    final List<Pair<PsiElement, TextRange>> result = new SmartList<Pair<PsiElement, TextRange>>();
    enumerate(host, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
      public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
        for (PsiLanguageInjectionHost.Shred place : places) {
          if (place.host == host) {
            result.add(new Pair<PsiElement, TextRange>(injectedPsi, place.getRangeInsideHost()));
          }
        }
      }
    });
    return result.isEmpty() ? null : result;
  }

  public static TextRange toTextRange(RangeMarker marker) {
    return new ProperTextRange(marker.getStartOffset(), marker.getEndOffset());
  }

  public static List<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>> getHighlightTokens(PsiFile file) {
    return file.getUserData(HIGHLIGHT_TOKENS);
  }

  // returns lexer elemet types with corresponsing ranges in encoded (injection host based) PSI 
  private static List<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>> obtainHighlightTokensFromLexer(Language language,
                                                                                                                 StringBuilder outChars,
                                                                                                                 List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers,
                                                                                                                 List<PsiLanguageInjectionHost.Shred> shreds,
                                                                                                                 VirtualFileWindow virtualFile,
                                                                                                                 Project project) {
    List<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>> tokens = new ArrayList<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>>(10);
    SyntaxHighlighter syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, (VirtualFile)virtualFile);
    Lexer lexer = syntaxHighlighter.getHighlightingLexer();
    lexer.start(outChars, 0, outChars.length(), 0);
    int hostNum = -1;
    int prevHostEndOffset = 0;
    PsiLanguageInjectionHost host = null;
    LiteralTextEscaper<? extends PsiLanguageInjectionHost> escaper = null;
    int prefixLength = 0;
    int suffixLength = 0;
    TextRange rangeInsideHost = null;
    int shredEndOffset = -1;
    for (IElementType tokenType = lexer.getTokenType(); tokenType != null; lexer.advance(), tokenType = lexer.getTokenType()) {
      TextRange range = new ProperTextRange(lexer.getTokenStart(), lexer.getTokenEnd());
      while (range != null && !range.isEmpty()) {
        if (range.getStartOffset() >= shredEndOffset) {
          hostNum++;
          shredEndOffset = shreds.get(hostNum).range.getEndOffset();
          prevHostEndOffset = range.getStartOffset();
          host = shreds.get(hostNum).host;
          escaper = escapers.get(hostNum);
          rangeInsideHost = shreds.get(hostNum).getRangeInsideHost();
          prefixLength = shreds.get(hostNum).prefix.length();
          suffixLength = shreds.get(hostNum).suffix.length();
        }
        //in prefix/suffix or spills over to next fragment
        if (range.getStartOffset() < prevHostEndOffset + prefixLength) {
          range = new TextRange(prevHostEndOffset + prefixLength, range.getEndOffset());
        }
        TextRange spilled = null;
        if (range.getEndOffset() >= shredEndOffset - suffixLength) {
          spilled = new TextRange(shredEndOffset, range.getEndOffset());
          range = new TextRange(range.getStartOffset(), shredEndOffset);
        }
        if (!range.isEmpty()) {
          int start = escaper.getOffsetInHost(range.getStartOffset() - prevHostEndOffset - prefixLength, rangeInsideHost);
          int end = escaper.getOffsetInHost(range.getEndOffset() - prevHostEndOffset - prefixLength, rangeInsideHost);
          if (end == -1) {
            end = rangeInsideHost.getEndOffset();
            tokens.add(Trinity.<IElementType, PsiLanguageInjectionHost, TextRange>create(tokenType, host, new ProperTextRange(start, end)));
            prevHostEndOffset = shredEndOffset;
          }
          else {
            TextRange rangeInHost = new ProperTextRange(start, end);
            tokens.add(Trinity.create(tokenType, host, rangeInHost));
          }
        }
        range = spilled;
      }
    }
    return tokens;
  }

  private static boolean isInjectedFragment(final PsiFile file) {
    return file.getViewProvider() instanceof MyFileViewProvider;
  }
  public static List<PsiLanguageInjectionHost.Shred> getShreds(PsiFile injectedFile) {
    FileViewProvider viewProvider = injectedFile.getViewProvider();
    if (!(viewProvider instanceof MyFileViewProvider)) return null;
    MyFileViewProvider myFileViewProvider = (MyFileViewProvider)viewProvider;
    synchronized (myFileViewProvider.myLock) {
      return myFileViewProvider.myShreds;
    }
  }

  private static class Place {
    private final PsiFile myInjectedPsi;
    private final List<PsiLanguageInjectionHost.Shred> myShreds;

    public Place(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> shreds) {
      myShreds = shreds;
      myInjectedPsi = injectedPsi;
    }
  }
  private interface Places extends List<Place> {}
  private static class PlacesImpl extends SmartList<Place> implements Places {}

  public static void enumerate(@NotNull PsiElement host, @NotNull PsiLanguageInjectionHost.InjectedPsiVisitor visitor) {
    PsiFile containingFile = host.getContainingFile();
    enumerate(host, containingFile, visitor, true);
  }

  public static void enumerate(@NotNull PsiElement host, @NotNull PsiFile containingFile, @NotNull PsiLanguageInjectionHost.InjectedPsiVisitor visitor, boolean probeUp) {
    //do not inject into nonphysical files except during completion
    if (!containingFile.isPhysical() && containingFile.getOriginalFile() == null) {
      final PsiElement context = containingFile.getContext();
      if (context == null) return;

      final PsiFile file = context.getContainingFile();
      if (file == null || !file.isPhysical() && file.getOriginalFile() == null) return;
    }
    Places places = probeElementsUp(host, containingFile, probeUp);
    if (places == null) return;
    for (Place place : places) {
      PsiFile injectedPsi = place.myInjectedPsi;
      List<PsiLanguageInjectionHost.Shred> pairs = place.myShreds;

      visitor.visit(injectedPsi, pairs);
    }
  }

  private static class MyFileViewProvider extends SingleRootFileViewProvider {
    private List<PsiLanguageInjectionHost.Shred> myShreds;
    private Project myProject;
    private final Object myLock = new Object();

    private MyFileViewProvider(@NotNull PsiManager psiManager, @NotNull VirtualFileWindow virtualFile, List<PsiLanguageInjectionHost.Shred> shreds) {
      super(psiManager, (VirtualFile)virtualFile);
      synchronized (myLock) {
        myShreds = new ArrayList<PsiLanguageInjectionHost.Shred>(shreds);
        myProject = myShreds.get(0).host.getProject();
      }
    }

    public void rootChanged(PsiFile psiFile) {
      super.rootChanged(psiFile);
      List<PsiLanguageInjectionHost.Shred> shreds;
      Project project;
      synchronized (myLock) {
        shreds = myShreds;
        project = myProject;
      }
      PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
      DocumentWindowImpl documentWindow = (DocumentWindowImpl)documentManager.getDocument(psiFile);
      assert documentWindow.getHostRanges().length == shreds.size();
      String[] changes = documentWindow.calculateMinEditSequence(psiFile.getText());
      //RangeMarker[] hostRanges = documentWindow.getHostRanges();
      assert changes.length == shreds.size();
      for (int i = 0; i < changes.length; i++) {
        String change = changes[i];
        if (change != null) {
          PsiLanguageInjectionHost.Shred shred = shreds.get(i);
          PsiLanguageInjectionHost host = shred.host;
          //RangeMarker hostRange = hostRanges[i];
          //TextRange hostTextRange = host.getTextRange();
          TextRange rangeInsideHost = shred.getRangeInsideHost();
          //TextRange rangeInsideHost = hostTextRange.intersection(toTextRange(hostRange)).shiftRight(-hostTextRange.getStartOffset());
          String newHostText = StringUtil.replaceSubstring(host.getText(), rangeInsideHost, change);
          host.fixText(newHostText);
        }
      }
    }

    public FileViewProvider clone() {
      final FileViewProvider copy = super.clone();
      final PsiFile psi = copy.getPsi(getBaseLanguage());
      psi.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, getPsi(getBaseLanguage()).getUserData(FileContextUtil.INJECTED_IN_ELEMENT));
      return copy;
    }

    @Nullable
    protected PsiFile getPsiInner(Language target) {
      // when FileManager rebuilds file map, all files temporarily become invalid, so this check is doomed
      PsiFile file = super.getPsiInner(target);
      //if (file == null || file.getContext() == null) return null;
      return file;
    }

    private void replace(VirtualFileWindowImpl virtualFile, List<PsiLanguageInjectionHost.Shred> shreds) {
      synchronized (myLock) {
        setVirtualFile(virtualFile);
        myShreds = new ArrayList<PsiLanguageInjectionHost.Shred>(shreds);
        myProject = shreds.get(0).host.getProject();
      }
    }

    private boolean isValid() {
      return !myProject.isDisposed();
    }
  }

  private static void patchLeafs(final ASTNode parsedNode,
                                 final List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers,
                                 final List<PsiLanguageInjectionHost.Shred> shreds) {
    final Map<LeafElement, String> newTexts = new THashMap<LeafElement, String>();
    final StringBuilder catLeafs = new StringBuilder();
    ((TreeElement)parsedNode).acceptTree(new RecursiveTreeElementVisitor(){
      int currentHostNum = -1;
      LeafElement prevElement;
      String prevElementTail;
      int prevHostsCombinedLength;
      TextRange shredHostRange;
      TextRange rangeInsideHost;
      String hostText;
      PsiLanguageInjectionHost.Shred shred;
      int prefixLength;
      {
        incHostNum(0);
      }

      protected boolean visitNode(TreeElement element) {
        return true;
      }

      @Override public void visitLeaf(LeafElement leaf) {
        String leafText = leaf.getText();
        catLeafs.append(leafText);
        TextRange range = leaf.getTextRange();
        int startOffsetInHost;
        while (true) {
          if (prefixLength > range.getStartOffset() && prefixLength < range.getEndOffset()) {
            //LOG.error("Prefix must not contain text that will be glued with the element body after parsing. " +
            //          "However, parsed element of "+leaf.getClass()+" contains "+(prefixLength-range.getStartOffset()) + " characters from the prefix. " +
            //          "Parsed text is '"+leaf.getText()+"'");
          }
          if (range.getStartOffset() < shredHostRange.getEndOffset() && shredHostRange.getEndOffset() < range.getEndOffset()) {
            //LOG.error("Suffix must not contain text that will be glued with the element body after parsing. " +
            //          "However, parsed element of "+leaf.getClass()+" contains "+(range.getEndOffset()-shredHostRange.getEndOffset()) + " characters from the suffix. " +
            //          "Parsed text is '"+leaf.getText()+"'");
          }

          int start = range.getStartOffset() - prevHostsCombinedLength;
          if (start < prefixLength) return;
          int end = range.getEndOffset();
          if (end > shred.range.getEndOffset() - shred.suffix.length() && end <= shred.range.getEndOffset()) return;
          startOffsetInHost = escapers.get(currentHostNum).getOffsetInHost(start - prefixLength, rangeInsideHost);

          if (startOffsetInHost != -1 && startOffsetInHost != rangeInsideHost.getEndOffset()) {
            break;
          }
          // no way next leaf might stand more than one shred apart
          incHostNum(range.getStartOffset());
        }
        String leafEncodedText = "";
        while (true) {
          if (range.getEndOffset() <= shred.range.getEndOffset()) {
            int end = range.getEndOffset() - prevHostsCombinedLength;
            if (end < prefixLength) {
              leafEncodedText += shred.prefix.substring(0, end);
            }
            else {
              int endOffsetInHost = escapers.get(currentHostNum).getOffsetInHost(end - prefixLength, rangeInsideHost);
              assert endOffsetInHost != -1;
              leafEncodedText += hostText.substring(startOffsetInHost, endOffsetInHost);
            }
            break;
          }
          String rest = hostText.substring(startOffsetInHost, rangeInsideHost.getEndOffset());
          leafEncodedText += rest;
          incHostNum(shred.range.getEndOffset());
          startOffsetInHost = shred.getRangeInsideHost().getStartOffset();
        }

        if (leaf.getElementType() == TokenType.WHITE_SPACE && prevElementTail != null) {
          // optimization: put all garbage into whitespace
          leafEncodedText = prevElementTail + leafEncodedText;
          newTexts.remove(prevElement);
          storeUnescapedTextFor(prevElement, null);
        }
        if (!Comparing.strEqual(leafText, leafEncodedText)) {
          newTexts.put(leaf, leafEncodedText);
          storeUnescapedTextFor(leaf, leafText);
        }
        if (leafEncodedText.startsWith(leafText) && leafEncodedText.length() != leafText.length()) {
          prevElementTail = leafEncodedText.substring(leafText.length());
        }
        else {
          prevElementTail = null;
        }
        prevElement = leaf;
      }

      private void incHostNum(int startOffset) {
        currentHostNum++;
        prevHostsCombinedLength = startOffset;
        shred = shreds.get(currentHostNum);
        shredHostRange = new ProperTextRange(TextRange.from(shred.prefix.length(), shred.getRangeInsideHost().getLength()));
        rangeInsideHost = shred.getRangeInsideHost();
        hostText = shred.host.getText();
        prefixLength = shredHostRange.getStartOffset();
      }
    });

    String nodeText = parsedNode.getText();
    assert nodeText.equals(catLeafs.toString()) : "Malformed PSI structure: leaf texts do not add up to the whole file text." +
                                                  "\nFile text (from tree)  :'"+nodeText+"'" +
                                                  "\nFile text (from PSI)   :'"+parsedNode.getPsi().getText()+"'" +
                                                  "\nLeaf texts concatenated:'"+catLeafs+"';" +
                                                  "\nFile root: "+parsedNode+
                                                  "\nLanguage: "+parsedNode.getPsi().getLanguage()+
                                                  "\nHost file: "+shreds.get(0).host.getContainingFile().getVirtualFile()
        ;
    for (LeafElement leaf : newTexts.keySet()) {
      String newText = newTexts.get(leaf);
      leaf.setText(newText);
    }
    ((TreeElement)parsedNode).acceptTree(new RecursiveTreeElementVisitor(){
      protected boolean visitNode(TreeElement element) {
        element.clearCaches();
        return true;
      }
    });
  }

  private static void storeUnescapedTextFor(final LeafElement leaf, final String leafText) {
    PsiElement psi = leaf.getPsi();
    if (psi != null) {
      psi.putUserData(InjectedLanguageManagerImpl.UNESCAPED_TEXT, leafText);
    }
  }

  public static Editor getEditorForInjectedLanguageNoCommit(@Nullable Editor editor, @Nullable PsiFile file) {
    if (editor == null || file == null || editor instanceof EditorWindow) return editor;

    int offset = editor.getCaretModel().getOffset();
    return getEditorForInjectedLanguageNoCommit(editor, file, offset);
  }

  public static Editor getEditorForInjectedLanguageNoCommit(@Nullable Editor editor, @Nullable PsiFile file, final int offset) {
    if (editor == null || file == null || editor instanceof EditorWindow) return editor;
    PsiFile injectedFile = findInjectedPsiNoCommit(file, offset);
    return getInjectedEditorForInjectedFile(editor, injectedFile);
  }

  @NotNull
  public static Editor getInjectedEditorForInjectedFile(@NotNull Editor editor, final PsiFile injectedFile) {
    if (injectedFile == null || editor instanceof EditorWindow) return editor;
    Document document = PsiDocumentManager.getInstance(editor.getProject()).getDocument(injectedFile);
    if (!(document instanceof DocumentWindowImpl)) return editor;
    DocumentWindowImpl documentWindow = (DocumentWindowImpl)document;
    SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasSelection()) {
      int selstart = selectionModel.getSelectionStart();
      int selend = selectionModel.getSelectionEnd();
      if (!documentWindow.containsRange(selstart, selend)) {
        // selection spreads out the injected editor range
        return editor;
      }
    }
    return EditorWindow.create(documentWindow, (EditorImpl)editor, injectedFile);
  }

  public static PsiFile findInjectedPsiNoCommit(@NotNull PsiFile host, int offset) {
    PsiElement injected = findInjectedElementNoCommit(host, offset);
    if (injected != null) {
      return injected.getContainingFile();
    }
    return null;
  }

  // consider injected elements
  public static PsiElement findElementAtNoCommit(@NotNull PsiFile file, int offset) {
    if (!isInjectedFragment(file)) {
      PsiElement injected = findInjectedElementNoCommit(file, offset);
      if (injected != null) {
        return injected;
      }
    }
    //PsiElement at = file.findElementAt(offset);
    FileViewProvider viewProvider = file.getViewProvider();
    return viewProvider.findElementAt(offset, viewProvider.getBaseLanguage());
  }

  private static final InjectedPsiProvider INJECTED_PSI_PROVIDER = new InjectedPsiProvider();
  private static Places probeElementsUp(@NotNull PsiElement element, @NotNull PsiFile hostPsiFile, boolean probeUp) {
    PsiManager psiManager = hostPsiFile.getManager();
    final Project project = psiManager.getProject();
    InjectedLanguageManagerImpl injectedManager = InjectedLanguageManagerImpl.getInstanceImpl(project);
    if (injectedManager == null) return null; //for tests

    for (PsiElement current = element; current != null && current != hostPsiFile; current = current.getParent()) {
      if ("EL".equals(current.getLanguage().getID())) break;
      ParameterizedCachedValue<Places,PsiElement> data = current.getUserData(INJECTED_PSI_KEY);
      Places places;
      if (data == null) {
        places = InjectedPsiProvider.doCompute(current, injectedManager, project, hostPsiFile);
        if (places != null) {
          ParameterizedCachedValue<Places, PsiElement> cachedValue =
              psiManager.getCachedValuesManager().createParameterizedCachedValue(INJECTED_PSI_PROVIDER, false);
          Document hostDocument = hostPsiFile.getViewProvider().getDocument();
          CachedValueProvider.Result<Places> result =
              new CachedValueProvider.Result<Places>(places, PsiModificationTracker.MODIFICATION_COUNT, hostDocument);
          ((ParameterizedCachedValueImpl<Places, PsiElement>)cachedValue).setValue(result);
          for (Place place : places) {
            for (PsiLanguageInjectionHost.Shred pair : place.myShreds) {
              pair.host.putUserData(INJECTED_PSI_KEY, cachedValue);
            }
          }
          current.putUserData(INJECTED_PSI_KEY, cachedValue);
        }
      }
      else {
        places = data.getValue(current);
      }
      if (places != null) {
        // check that injections found intersect with queried element
        TextRange elementRange = element.getTextRange();
        for (Place place : places) {
          for (PsiLanguageInjectionHost.Shred shred : place.myShreds) {
            if (shred.host.getTextRange().intersects(elementRange)) {
              return places;
            }
          }
        }
      }
      if (!probeUp) break;
    }
    return null;
  }

  public static PsiElement findInjectedElementNoCommitWithOffset(@NotNull PsiFile file, final int offset) {
    if (isInjectedFragment(file)) return null;
    final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(file.getProject());

    PsiElement element = file.getViewProvider().findElementAt(offset, file.getLanguage());
    return element == null ? null : findInside(element, file, offset, documentManager);
  }

  public static PsiElement findInjectedElementNoCommit(@NotNull PsiFile file, final int offset) {
    PsiElement inj = findInjectedElementNoCommitWithOffset(file, offset);
    if (inj != null) return inj;
    if (offset != 0) {
      inj = findInjectedElementNoCommitWithOffset(file, offset - 1);
    }
    return inj;
  }

  private static PsiElement findInside(@NotNull PsiElement element, @NotNull PsiFile file, final int offset, @NotNull final PsiDocumentManager documentManager) {
    final Ref<PsiElement> out = new Ref<PsiElement>();
    enumerate(element, file, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
      public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
        for (PsiLanguageInjectionHost.Shred place : places) {
          TextRange hostRange = place.host.getTextRange();
          if (hostRange.cutOut(place.getRangeInsideHost()).grown(1).contains(offset)) {
            DocumentWindowImpl document = (DocumentWindowImpl)documentManager.getCachedDocument(injectedPsi);
            int injectedOffset = document.hostToInjected(offset);
            PsiElement injElement = injectedPsi.findElementAt(injectedOffset);
            out.set(injElement == null ? injectedPsi : injElement);
          }
        }
      }
    }, true);
    return out.get();
  }

  private static class InjectedPsiProvider implements ParameterizedCachedValueProvider<Places, PsiElement> {
    public CachedValueProvider.Result<Places> compute(PsiElement element) {
      PsiFile hostPsiFile = element.getContainingFile();
      if (hostPsiFile == null) return null;
      FileViewProvider viewProvider = hostPsiFile.getViewProvider();
      final DocumentEx hostDocument = (DocumentEx)viewProvider.getDocument();
      if (hostDocument == null) return null;

      PsiManager psiManager = viewProvider.getManager();
      final Project project = psiManager.getProject();
      InjectedLanguageManagerImpl injectedManager = InjectedLanguageManagerImpl.getInstanceImpl(project);
      if (injectedManager == null) return null; //for tests
      final Places result = doCompute(element, injectedManager, project, hostPsiFile);

      return new CachedValueProvider.Result<Places>(result, PsiModificationTracker.MODIFICATION_COUNT, hostDocument);
    }

    @Nullable
    private static Places doCompute(final PsiElement element, InjectedLanguageManagerImpl injectedManager, Project project,
                                    PsiFile hostPsiFile) {
      MyInjProcessor processor = new MyInjProcessor(injectedManager, project, hostPsiFile);
      injectedManager.processInPlaceInjectorsFor(element, processor);
      return processor.hostRegistrar == null ? null : processor.hostRegistrar.result;
    }
    private static class MyInjProcessor implements InjectedLanguageManagerImpl.InjProcessor {
      private MyMultiHostRegistrar hostRegistrar;
      private final InjectedLanguageManagerImpl myInjectedManager;
      private final Project myProject;
      private final PsiFile myHostPsiFile;

      public MyInjProcessor(InjectedLanguageManagerImpl injectedManager, Project project, PsiFile hostPsiFile) {
        myInjectedManager = injectedManager;
        myProject = project;
        myHostPsiFile = hostPsiFile;
      }

      public boolean process(PsiElement element, MultiHostInjector injector) {
        if (hostRegistrar == null) {
          hostRegistrar = new MyMultiHostRegistrar(myProject, myInjectedManager, myHostPsiFile);
        }
        injector.getLanguagesToInject(hostRegistrar, element);
        return hostRegistrar.result == null;
      }
    }

    private static class MyMultiHostRegistrar implements MultiHostRegistrar {
      private Places result;
      private Language myLanguage;
      private List<TextRange> relevantRangesInHostDocument;
      private List<String> prefixes;
      private List<String> suffixes;
      private List<PsiLanguageInjectionHost> injectionHosts;
      private List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers;
      private List<PsiLanguageInjectionHost.Shred> shreds;
      private StringBuilder outChars;
      boolean isOneLineEditor;
      boolean cleared;
      private final Project myProject;
      private final PsiManager myPsiManager;
      private DocumentEx myHostDocument;
      private VirtualFile myHostVirtualFile;
      private final InjectedLanguageManagerImpl myInjectedManager;
      private final PsiFile myHostPsiFile;

      public MyMultiHostRegistrar(Project project, InjectedLanguageManagerImpl injectedManager, PsiFile hostPsiFile) {
        myProject = project;
        myInjectedManager = injectedManager;
        myHostPsiFile = PsiUtilBase.getTemplateLanguageFile(hostPsiFile);
        myPsiManager = myHostPsiFile.getManager();
        cleared = true;
      }

      @NotNull
      public MultiHostRegistrar startInjecting(@NotNull Language language) {
        relevantRangesInHostDocument = new SmartList<TextRange>();
        prefixes = new SmartList<String>();
        suffixes = new SmartList<String>();
        injectionHosts = new SmartList<PsiLanguageInjectionHost>();
        escapers = new SmartList<LiteralTextEscaper<? extends PsiLanguageInjectionHost>>();
        shreds = new SmartList<PsiLanguageInjectionHost.Shred>();
        outChars = new StringBuilder();

        if (!cleared) {
          clear();
          throw new IllegalStateException("Seems you haven't called doneInjecting()");
        }

        if (LanguageParserDefinitions.INSTANCE.forLanguage(language) == null) {
          throw new UnsupportedOperationException("Cannot inject language '" + language + "' since its getParserDefinition() returns null");
        }
        myLanguage = language;

        FileViewProvider viewProvider = myHostPsiFile.getViewProvider();
        myHostVirtualFile = viewProvider.getVirtualFile();
        myHostDocument = (DocumentEx)viewProvider.getDocument();
        assert myHostDocument != null : myHostPsiFile + "; " + viewProvider;
        return this;
      }

      private void clear() {
        relevantRangesInHostDocument.clear();
        prefixes.clear();
        suffixes.clear();
        injectionHosts.clear();
        escapers.clear();
        shreds.clear();
        outChars.setLength(0);
        isOneLineEditor = false;
        myLanguage = null;

        cleared = true;
      }

      @NotNull
      public MultiHostRegistrar addPlace(@NonNls @Nullable String prefix,
                                         @NonNls @Nullable String suffix,
                                         @NotNull PsiLanguageInjectionHost host,
                                         @NotNull TextRange rangeInsideHost) {
        ProperTextRange.assertProperRange(rangeInsideHost);
        PsiFile containingFile = PsiUtilBase.getTemplateLanguageFile(host);
        assert containingFile == myHostPsiFile : "Trying to inject into foreign file: "+containingFile+" while processing injections for "+myHostPsiFile;
        TextRange hostTextRange = host.getTextRange();
        if (!hostTextRange.contains(rangeInsideHost.shiftRight(hostTextRange.getStartOffset()))) {
          clear();
          throw new IllegalArgumentException("rangeInsideHost must lie within host text range. rangeInsideHost:"+rangeInsideHost+"; host textRange:"+
                                             hostTextRange);
        }
        if (myLanguage == null) {
          clear();
          throw new IllegalStateException("Seems you haven't called startInjecting()");
        }

        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";
        prefixes.add(prefix);
        suffixes.add(suffix);
        cleared = false;
        injectionHosts.add(host);
        int startOffset = outChars.length();
        outChars.append(prefix);
        LiteralTextEscaper<? extends PsiLanguageInjectionHost> textEscaper = host.createLiteralTextEscaper();
        escapers.add(textEscaper);
        isOneLineEditor |= textEscaper.isOneLine();
        TextRange relevantRange = textEscaper.getRelevantTextRange().intersection(rangeInsideHost);
        if (relevantRange == null) {
          relevantRange = TextRange.from(textEscaper.getRelevantTextRange().getStartOffset(), 0);
        }
        else {
          boolean result = textEscaper.decode(relevantRange, outChars);
          if (!result) {
            // if there are invalid chars, adjust the range
            int offsetInHost = textEscaper.getOffsetInHost(outChars.length() - startOffset, rangeInsideHost);
            relevantRange = relevantRange.intersection(new ProperTextRange(0, offsetInHost));
          }
        }
        outChars.append(suffix);
        int endOffset = outChars.length();
        TextRange relevantRangeInHost = relevantRange.shiftRight(hostTextRange.getStartOffset());
        relevantRangesInHostDocument.add(relevantRangeInHost);
        RangeMarker relevantMarker = myHostDocument.createRangeMarker(relevantRangeInHost);
        relevantMarker.setGreedyToLeft(true);
        relevantMarker.setGreedyToRight(true);
        shreds.add(new PsiLanguageInjectionHost.Shred(host, relevantMarker, prefix, suffix, new ProperTextRange(startOffset, endOffset)));
        return this;
      }

      public void doneInjecting() {
        try {
          if (shreds.isEmpty()) {
            throw new IllegalStateException("Seems you haven't called addPlace()");
          }
          PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);
          assert ArrayUtil.indexOf(documentManager.getUncommittedDocuments(), myHostDocument) == -1;
          assert myHostPsiFile.getText().equals(myHostDocument.getText());
          assert relevantRangesInHostDocument.size() == shreds.size();
          
          DocumentWindowImpl documentWindow = new DocumentWindowImpl(myHostDocument, isOneLineEditor, prefixes, suffixes, relevantRangesInHostDocument);
          VirtualFileWindowImpl virtualFile = (VirtualFileWindowImpl)myInjectedManager.createVirtualFile(myLanguage, myHostVirtualFile, documentWindow, outChars);
          myLanguage = LanguageSubstitutors.INSTANCE.substituteLanguage(myLanguage, virtualFile, myProject);
          virtualFile.setLanguage(myLanguage);

          DocumentImpl decodedDocument = new DocumentImpl(outChars);
          FileDocumentManagerImpl.registerDocument(decodedDocument, virtualFile);

          SingleRootFileViewProvider viewProvider = new MyFileViewProvider(myPsiManager, virtualFile, shreds);
          ParserDefinition parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(myLanguage);
          assert parserDefinition != null;
          PsiFile psiFile = parserDefinition.createFile(viewProvider);
          assert isInjectedFragment(psiFile) : psiFile.getViewProvider();

          SmartPsiElementPointer<PsiLanguageInjectionHost> pointer = createHostSmartPointer(injectionHosts.get(0));
          psiFile.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, pointer);

          final ASTNode parsedNode = psiFile.getNode();
          assert parsedNode instanceof FileElement : parsedNode;

          String documentText = documentWindow.getText();
          assert outChars.toString().equals(parsedNode.getText()) : "Before patch: doc:\n" + documentText + "\n---PSI:\n" + parsedNode.getText() + "\n---chars:\n"+outChars;
          try {
            patchLeafs(parsedNode, escapers, shreds);
          }
          catch (ProcessCanceledException e) {
            throw e;
          }
          catch (RuntimeException e) {
            throw new RuntimeException("Patch error, lang="+myLanguage+";\n "+myHostVirtualFile+"; places:"+injectionHosts+";\n ranges:"+relevantRangesInHostDocument, e);
          }
          assert parsedNode.getText().equals(documentText) : "After patch: doc:\n" + documentText + "\n---PSI:\n" + parsedNode.getText() + "\n---chars:\n"+outChars;

          ((FileElement)parsedNode).setManager((PsiManagerEx)myPsiManager);
          virtualFile.setContent(null, documentWindow.getText(), false);
          FileDocumentManagerImpl.registerDocument(documentWindow, virtualFile);
          synchronized (PsiLock.LOCK) {
            psiFile = registerDocument(documentWindow, psiFile, virtualFile, shreds, myHostPsiFile, documentManager);
            MyFileViewProvider myFileViewProvider = (MyFileViewProvider)psiFile.getViewProvider();
            myFileViewProvider.setVirtualFile(virtualFile);
            myFileViewProvider.forceCachedPsi(psiFile);
          }

          try {
            List<Trinity<IElementType, PsiLanguageInjectionHost, TextRange>> tokens =
                obtainHighlightTokensFromLexer(myLanguage, outChars, escapers, shreds, virtualFile, myProject);
            psiFile.putUserData(HIGHLIGHT_TOKENS, tokens);
          }
          catch (ProcessCanceledException e) {
            throw e;
          }
          catch (RuntimeException e) {
            throw new RuntimeException("Patch error, lang="+myLanguage+";\n "+myHostVirtualFile+"; places:"+injectionHosts+";\n ranges:"+relevantRangesInHostDocument, e);
          }

          PsiDocumentManagerImpl.checkConsistency(psiFile, documentWindow);

          Place place = new Place(psiFile, new ArrayList<PsiLanguageInjectionHost.Shred>(shreds));
          if (result == null) {
            result = new PlacesImpl();
          }
          result.add(place);
        }
        finally {
          clear();
        }
      }
    }
  }

  private static <T extends PsiLanguageInjectionHost> SmartPsiElementPointer<T> createHostSmartPointer(final T host) {
    return host.isPhysical()
           ? SmartPointerManager.getInstance(host.getProject()).createSmartPsiElementPointer(host)
           : new SmartPsiElementPointer<T>() {
             public T getElement() {
               return host;
             }

             public PsiFile getContainingFile() {
               return host.getContainingFile();
             }
           };
  }

  private static final Key<List<DocumentWindow>> INJECTED_DOCS_KEY = Key.create("INJECTED_DOCS_KEY");
  private static final Key<List<RangeMarker>> INJECTED_REGIONS_KEY = Key.create("INJECTED_REGIONS_KEY");
  @NotNull
  public static List<DocumentWindow> getCachedInjectedDocuments(@NotNull PsiFile hostPsiFile) {
    List<DocumentWindow> injected = hostPsiFile.getUserData(INJECTED_DOCS_KEY);
    if (injected == null) {
      injected = ((UserDataHolderEx)hostPsiFile).putUserDataIfAbsent(INJECTED_DOCS_KEY, new CopyOnWriteArrayList<DocumentWindow>());
    }
    return injected;
  }

  public static void commitAllInjectedDocuments(Document hostDocument, Project project) {
    List<RangeMarker> injected = getCachedInjectedRegions(hostDocument);
    if (injected.isEmpty()) return;

    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
    PsiFile hostPsiFile = documentManager.getPsiFile(hostDocument);
    assert hostPsiFile != null;
    for (RangeMarker rangeMarker : injected) {
      PsiElement element = rangeMarker.isValid() ? hostPsiFile.findElementAt(rangeMarker.getStartOffset()) : null;
      if (element == null) {
        injected.remove(rangeMarker);
        continue;
      }
      // it is here reparse happens and old file contents replaced
      enumerate(element, hostPsiFile, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
        public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
          PsiDocumentManagerImpl.checkConsistency(injectedPsi, injectedPsi.getViewProvider().getDocument());
        }
      }, true);
    }
    PsiDocumentManagerImpl.checkConsistency(hostPsiFile, hostDocument);
  }

  public static void clearCaches(PsiFile injected) {
    VirtualFileWindow virtualFile = (VirtualFileWindow)injected.getVirtualFile();
    PsiManagerEx psiManagerEx = (PsiManagerEx)injected.getManager();
    psiManagerEx.getFileManager().setViewProvider((VirtualFile)virtualFile, null);
    Project project = psiManagerEx.getProject();
    if (!project.isDisposed()) {
      InjectedLanguageManagerImpl.getInstanceImpl(project).clearCaches(virtualFile);
    }
  }

  private static PsiFile registerDocument(final DocumentWindowImpl documentWindow,
                                          final PsiFile injectedPsi,
                                          VirtualFileWindowImpl virtualFile,
                                          List<PsiLanguageInjectionHost.Shred> shreds,
                                          final PsiFile hostPsiFile,
                                          PsiDocumentManager documentManager) {
    DocumentEx hostDocument = documentWindow.getDelegate();
    List<DocumentWindow> injected = getCachedInjectedDocuments(hostPsiFile);

    for (int i = injected.size()-1; i>=0; i--) {
      DocumentWindowImpl oldDocument = (DocumentWindowImpl)injected.get(i);
      PsiFileImpl oldFile = (PsiFileImpl)documentManager.getCachedPsiFile(oldDocument);
      FileViewProvider oldViewProvider;

      if (oldFile == null ||
          !oldFile.isValid() ||
          !((oldViewProvider = oldFile.getViewProvider()) instanceof MyFileViewProvider) ||
          !((MyFileViewProvider)oldViewProvider).isValid()
          ) {
        injected.remove(i);
        Disposer.dispose(oldDocument);
        continue;
      }

      ASTNode injectedNode = injectedPsi.getNode();
      ASTNode oldFileNode = oldFile.getNode();
      assert injectedNode != null;
      assert oldFileNode != null;
      if (oldDocument.areRangesEqual(documentWindow)) {
        if (oldFile.getFileType() != injectedPsi.getFileType() || oldFile.getLanguage() != injectedPsi.getLanguage()) {
          injected.remove(i);
          Disposer.dispose(oldDocument);
          continue;
        }
        oldFile.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, injectedPsi.getUserData(FileContextUtil.INJECTED_IN_ELEMENT));
        if (!isPSItheSame(injectedNode, oldFileNode)) {
          // replace psi
          FileElement newFileElement = (FileElement)injectedNode;
          FileElement oldFileElement = oldFile.getTreeElement();

          if (oldFileElement.getFirstChildNode() != null) {
            TreeUtil.removeRange(oldFileElement.getFirstChildNode(), null);
          }
          final ASTNode firstChildNode = newFileElement.getFirstChildNode();
          if (firstChildNode != null) {
            TreeUtil.addChildren(oldFileElement, (TreeElement)firstChildNode);
          }
          oldFileElement.setCharTable(newFileElement.getCharTable());
          FileDocumentManagerImpl.registerDocument(documentWindow, oldFile.getVirtualFile());

          MyFileViewProvider viewProvider = (MyFileViewProvider)oldViewProvider;
          viewProvider.replace(virtualFile, shreds);

          oldFile.subtreeChanged();
        }
        return oldFile;
      }
    }
    injected.add(documentWindow);
    List<RangeMarker> injectedRegions = getCachedInjectedRegions(hostDocument);
    RangeMarker newMarker = documentWindow.getHostRanges()[0];
    TextRange newRange = toTextRange(newMarker);
    for (int i = 0; i < injectedRegions.size(); i++) {
      RangeMarker stored = injectedRegions.get(i);
      TextRange storedRange = toTextRange(stored);
      if (storedRange.intersects(newRange)) {
        injectedRegions.set(i, newMarker);
        break;
      }
      if (storedRange.getStartOffset() > newRange.getEndOffset()) {
        injectedRegions.add(i, newMarker);
        break;
      }
    }
    if (injectedRegions.isEmpty() || newRange.getStartOffset() > injectedRegions.get(injectedRegions.size()-1).getEndOffset()) {
      injectedRegions.add(newMarker);
    }
    return injectedPsi;
  }

  private static boolean isPSItheSame(ASTNode injectedNode, ASTNode oldFileNode) {
    boolean textSame = injectedNode.getText().equals(oldFileNode.getText());

    //boolean psiSame = comparePSI(injectedNode, oldFileNode);
    //if (psiSame != textSame) {
    //  throw new RuntimeException(textSame + ";" + psiSame);
    //}
    return textSame;
  }

  private static boolean comparePSI(ASTNode injectedNode, ASTNode oldFileNode) {
    if (injectedNode instanceof LeafElement) {
      return oldFileNode instanceof LeafElement &&
             injectedNode.getElementType().equals(oldFileNode.getElementType()) &&
             injectedNode.getText().equals(oldFileNode.getText());
    }
    if (!(injectedNode instanceof CompositeElement) || !(oldFileNode instanceof CompositeElement)) return false;
    CompositeElement element1 = (CompositeElement)injectedNode;
    CompositeElement element2 = (CompositeElement)oldFileNode;
    TreeElement child1 = element1.getFirstChildNode();
    TreeElement child2 = element2.getFirstChildNode();
    while (child1  != null && child2 != null) {
      if (!comparePSI(child1, child2)) return false;
      child1 = child1.getTreeNext();
      child2 = child2.getTreeNext();
    }

    return child1 == null && child2 == null;
  }

  private static List<RangeMarker> getCachedInjectedRegions(Document hostDocument) {
    List<RangeMarker> injectedRegions = hostDocument.getUserData(INJECTED_REGIONS_KEY);
    if (injectedRegions == null) {
      injectedRegions = ((UserDataHolderEx)hostDocument).putUserDataIfAbsent(INJECTED_REGIONS_KEY, new CopyOnWriteArrayList<RangeMarker>());
    }
    return injectedRegions;
  }

  public static Editor openEditorFor(PsiFile file, Project project) {
    Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    // may return editor injected in current selection in the host editor, not for the file passed as argument
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return null;
    }
    if (virtualFile instanceof VirtualFileWindow) {
      virtualFile = ((VirtualFileWindow)virtualFile).getDelegate();
    }
    Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile, -1), false);
    if (editor == null || editor instanceof EditorWindow) return editor;
    if (document instanceof DocumentWindowImpl) {
      return EditorWindow.create((DocumentWindowImpl)document, (EditorImpl)editor, file);
    }
    return editor;
  }

  public static PsiFile getTopLevelFile(PsiElement element) {
    PsiFile containingFile = element.getContainingFile();
    Document document = PsiDocumentManager.getInstance(element.getProject()).getCachedDocument(containingFile);
    if (document instanceof DocumentWindow) {
      PsiElement host = containingFile.getContext();
      if (host != null) containingFile = host.getContainingFile();
    }
    return containingFile;
  }
  public static boolean isInInjectedLanguagePrefixSuffix(final PsiElement element) {
    PsiFile injectedFile = element.getContainingFile();
    if (injectedFile == null || !isInjectedFragment(injectedFile)) return false;
    TextRange elementRange = element.getTextRange();
    List<TextRange> editables = InjectedLanguageManager.getInstance(injectedFile.getProject())
        .intersectWithAllEditableFragments(injectedFile, elementRange);
    int combinedEdiablesLength = 0;
    for (TextRange editable : editables) {
      combinedEdiablesLength += editable.getLength();
    }

    return combinedEdiablesLength != elementRange.getLength();
  }

  public static boolean isSelectionIsAboutToOverflowInjectedFragment(EditorWindow injectedEditor) {
    int selStart = injectedEditor.getSelectionModel().getSelectionStart();
    int selEnd = injectedEditor.getSelectionModel().getSelectionEnd();

    DocumentWindow document = injectedEditor.getDocument();

    boolean isStartOverflows = selStart == 0;
    if (!isStartOverflows) {
      int hostPrev = document.injectedToHost(selStart - 1);
      isStartOverflows = document.hostToInjected(hostPrev) == selStart;
    }

    boolean isEndOverflows = selEnd == document.getTextLength();
    if (!isEndOverflows) {
      int hostNext = document.injectedToHost(selEnd + 1);
      isEndOverflows = document.hostToInjected(hostNext) == selEnd;
    }

    return isStartOverflows && isEndOverflows;
  }
}
