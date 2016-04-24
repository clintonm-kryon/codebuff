package org.antlr.codebuff;

import org.antlr.codebuff.gui.GUIController;
import org.antlr.codebuff.misc.CodeBuffTokenStream;
import org.antlr.codebuff.misc.ParentSiblingListKey;
import org.antlr.codebuff.misc.SiblingListStats;
import org.antlr.codebuff.walkers.CollectSiblingLists;
import org.antlr.codebuff.walkers.CollectTokenDependencies;
import org.antlr.v4.runtime.ANTLRFileStream;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.antlr.codebuff.misc.BuffUtils.filter;

/** Grammar must have WS/comments on hidden channel
 *
 * Testing:
 *
 * Tool  -dbg  -antlr     ../corpus/antlr4/training      grammars/org/antlr/codebuff/tsql.g4
 * Tool  -dbg  -sqlite    ../corpus/sqlite/training      ../corpus/sqlite/testing/t1.sql
 * Tool  -dbg  -tsql      ../corpus/tsql/training        ../corpus/tsql/testing/select1.sql
 * Tool  -dbg  -plsql     ../corpus/plsql/training       ../corpus/plsql/testing/condition15.sql
 * Tool  -dbg  -java      ../corpus/java/training/stringtemplate4     src/org/antlr/codebuff/Tool.java
 * Tool  -dbg  -java      ../corpus/java/training/stringtemplate4     ../corpus/java/training/stringtemplate4/org/stringtemplate/v4/AutoIndentWriter.java
 */
public class Tool {
	public static final int DOCLIST_RANDOM_SEED = 951413; // need randomness but use same seed to get reproducibility

	public static boolean showFileNames = false;
	public static boolean showTokens = false;

	static class LangDescriptor {
		String name;
		String fileRegex;
		Class<? extends Lexer> lexerClass;
		Class<? extends Parser> parserClass;
		String startRuleName;
		int tabSize;

		public LangDescriptor(String name,
		                      String fileRegex,
		                      Class<? extends Lexer> lexerClass,
		                      Class<? extends Parser> parserClass,
		                      String startRuleName,
		                      int tabSize)
		{
			this.name = name;
			this.fileRegex = fileRegex;
			this.lexerClass = lexerClass;
			this.parserClass = parserClass;
			this.startRuleName = startRuleName;
			this.tabSize = tabSize;
		}
	}

	public static LangDescriptor[] languages = new LangDescriptor[] {
		new LangDescriptor("java", ".*\\.java", JavaLexer.class, JavaParser.class, "compilationUnit", 4),
		new LangDescriptor("antlr", ".*\\.g4", ANTLRv4Lexer.class, ANTLRv4Parser.class, "grammarSpec", 4),
		new LangDescriptor("sqlite", ".*\\.sql", SQLiteLexer.class, SQLiteParser.class, "parse", 4),
		new LangDescriptor("tsql", ".*\\.sql", tsqlLexer.class, tsqlParser.class, "tsql_file", 4),
		new LangDescriptor("plsql", ".*\\.sql", plsqlLexer.class, plsqlParser.class, "compilation_unit", 4)
	};

	public static void main(String[] args)
		throws Exception
	{
		if ( args.length<2 ) {
			System.err.println("ExtractFeatures [-dbg] [-java|-antlr|-sqlite|-tsql|-plsql] root-dir-of-samples test-file");
		}
		int tabSize = 4; // TODO: MAKE AN ARGUMENT
		int arg = 0;
		boolean collectAnalysis = false;
		if ( args[arg].equals("-dbg") ) {
			collectAnalysis = true;
			arg++;
		}
		String language = args[arg++];
		language = language.substring(1);
		String corpusDir = args[arg++];
		String testFilename = args[arg];
		String output = "???";
		Corpus corpus;
		InputDocument testDoc;
		GUIController controller;
		List<TokenPositionAnalysis> analysisPerToken;
		Pair<String, List<TokenPositionAnalysis>> results;
		LangDescriptor lang = null;
		long start, stop;
		for (int i = 0; i<languages.length; i++) {
			if ( languages[i].name.equals(language) ) {
				lang = languages[i];
				break;
			}
		}
		if ( lang!=null ) {
			corpus = train(corpusDir, lang.fileRegex, lang.lexerClass, lang.parserClass, lang.startRuleName, lang.tabSize, true);
			testDoc = load(testFilename, tabSize);
			start = System.nanoTime();
			results = format(corpus, testDoc, lang.lexerClass, lang.parserClass, lang.startRuleName, lang.tabSize, collectAnalysis);
			stop = System.nanoTime();
			output = results.a;
			analysisPerToken = results.b;
			controller = new GUIController(analysisPerToken, testDoc, output, lang.lexerClass);
			controller.show();
//			System.out.println(output);
			System.out.printf("formatting time %ds\n", (stop-start)/1_000_000);
			System.out.printf("classify calls %d, hits %d rate %f\n",
							  kNNClassifier.nClassifyCalls, kNNClassifier.nClassifyCacheHits,
							  kNNClassifier.nClassifyCacheHits/(float)kNNClassifier.nClassifyCalls);
			System.out.printf("kNN calls %d, hits %d rate %f\n",
							  kNNClassifier.nNNCalls, kNNClassifier.nNNCacheHits,
							  kNNClassifier.nNNCacheHits/(float)kNNClassifier.nNNCalls);
		}
	}

	/** Given a corpus, format the document by tokenizing and using the
	 *  corpus to locate newline and whitespace injection points.
	 */
	public static Pair<String,List<TokenPositionAnalysis>> format(Corpus corpus, InputDocument testDoc,
	                                                              Class<? extends Lexer> lexerClass,
	                                                              Class<? extends Parser> parserClass,
	                                                              String startRuleName,
	                                                              int tabSize,
	                                                              boolean collectAnalysis)
		throws Exception
	{
		return format(corpus, testDoc, lexerClass, parserClass, startRuleName, tabSize, true, collectAnalysis);
	}

	public static Pair<String,List<TokenPositionAnalysis>> format(Corpus corpus,
	                                                              InputDocument testDoc,
	                                                              Class<? extends Lexer> lexerClass,
	                                                              Class<? extends Parser> parserClass,
	                                                              String startRuleName,
	                                                              int tabSize,
	                                                              boolean showFormattedResult,
	                                                              boolean collectAnalysis)
		throws Exception
	{
		testDoc.corpus = corpus;
		parse(testDoc, lexerClass, parserClass, startRuleName);
		Formatter formatter = new Formatter(corpus, testDoc, tabSize, collectAnalysis);
		String formattedOutput = formatter.format();
		List<TokenPositionAnalysis> analysisPerToken = formatter.getAnalysisPerToken();
		dumpAccuracy(testDoc, analysisPerToken);

		testDoc.dumpIncorrectWS = false;
		double d = docDiff(testDoc.content, formattedOutput, lexerClass);
		if (showFormattedResult) System.out.println("Diff is "+d);

		List<Token> wsTokens = filter(formatter.originalTokens.getTokens(),
		                              t->t.getChannel()!=Token.DEFAULT_CHANNEL);
		String originalWS = tokenText(wsTokens);

		CommonTokenStream formatted_tokens = tokenize(formattedOutput, lexerClass);
		wsTokens = filter(formatted_tokens.getTokens(),
		                  t->t.getChannel()!=Token.DEFAULT_CHANNEL);
		String formattedWS = tokenText(wsTokens);

		float editDistance = levenshteinDistance(originalWS, formattedWS);
		System.out.println("Levenshtein distance of ws: "+editDistance);
		editDistance = levenshteinDistance(testDoc.content, formattedOutput);
		System.out.println("Levenshtein distance: "+editDistance);
		System.out.println("ws len orig="+originalWS.length()+", "+formattedWS.length());

		return new Pair<>(formattedOutput, analysisPerToken);
	}

	public static void dumpAccuracy(InputDocument testDoc, List<TokenPositionAnalysis> analysisPerToken) {
		System.out.println("num real tokens from 1: " +getNumberRealTokens(testDoc.tokens, 1, testDoc.tokens.size()-2)); // don't include first token nor EOF
		int n = 0; // should be number of real tokens - 1 (we don't process 1st token)
		int n_align_compares = 0;
		int correct_ws = 0;
		int n_none = 0;
		int n_nl = 0;
		int n_sp = 0;
		int correct_none = 0;
		int correct_nl = 0;
		int correct_sp = 0;
		int correct_align = 0;
		/*
		 predicted  |   actual  |   match
		 ---------  -   ------  -   ------
		            |           |     x
		            |   ' '     |
		            |   '\n'    |
		    '\n'    |           |
		    '\n'    |   ' '     |
		    '\n'    |   '\n'    |     x
		    ' '     |           |
		    ' '     |   ' '     |     x
		    ' '     |   '\n'    |
		 */
		for (TokenPositionAnalysis a : analysisPerToken) {
			if ( a==null ) continue;
			n++;
			if ( a.actualWS==0 ) {
				n_none++;
			}
			else if ( (a.actualWS&0xFF)==Trainer.CAT_INJECT_NL ) {
				n_nl++;
			}
			else if ( (a.actualWS&0xFF)==Trainer.CAT_INJECT_WS ) {
				n_sp++;
			}

			if ( a.wsPrediction==0 && a.wsPrediction==a.actualWS ) {
				correct_none++;
			}
			else if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_NL && a.wsPrediction==a.actualWS ) {
				correct_nl++;
			}
			else if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_WS && a.wsPrediction==a.actualWS ) {
				correct_sp++;
			}
			if ( a.wsPrediction==a.actualWS ) {
				correct_ws++;
			}

			if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_NL ) {
				n_align_compares++;
				// if we predicted newline *and* actual was newline, check alignment misclassification
				// Can't compare if both aren't supposed to align. If we predict '\n' but actual is ' ',
				// alignment will always fail to match. Similarly, if we predict no-'\n' but actual is '\n',
				// we didn't compute align so can't compare.
				if ( a.alignPrediction==a.actualAlign ) {
					correct_align++;
				}
			}
		}
		float none_accuracy = correct_none/(float) n_none;
		System.out.printf("correct none / num none = %d/%d, %4.3f%%\n",
		                  correct_none, n_none, none_accuracy*100);
		float nl_accuracy = correct_nl/(float) n_nl;
		System.out.printf("correct nl / num nl = %d/%d, %4.3f%%\n",
		                  correct_nl, n_nl, nl_accuracy*100);
		float sp_accuracy = correct_sp/(float) n_sp;
		System.out.printf("correct sp / num ws = %d/%d, %4.3f%%\n",
		                  correct_sp, n_sp, sp_accuracy*100);

		double overall_ws_accuracy = correct_ws/(float)n;
		System.out.printf("overall ws correct = %d/%d %4.3f%%\n",
		                  correct_ws, n, overall_ws_accuracy*100);

		double align_accuracy = correct_align / (float)n_align_compares;
		System.out.printf("align correct = %d/%d %4.3f%%\n",
		                  correct_align, n_align_compares, align_accuracy*100.0);
	}

	public static Corpus train(String rootDir,
	                           String fileRegex,
							   Class<? extends Lexer> lexerClass,
							   Class<? extends Parser> parserClass,
							   String startRuleName,
							   int tabSize,
	                           boolean shuffleFeatureVectors)
		throws Exception
	{
		List<String> allFiles = getFilenames(new File(rootDir), fileRegex);
		List<InputDocument> documents = load(allFiles, tabSize);

		// Parse all documents into parse trees before training begins
		for (InputDocument doc : documents) {
			if ( showFileNames ) System.out.println(doc);
			parse(doc, lexerClass, parserClass, startRuleName);
		}

		// Walk all documents to compute matching token dependencies (we need this for feature computation)
		// While we're at it, find sibling lists
		Vocabulary vocab = getLexer(lexerClass, null).getVocabulary();
		String[] ruleNames = getParser(parserClass, null).getRuleNames();
		CollectTokenDependencies collectTokenDependencies = new CollectTokenDependencies(vocab, ruleNames);
		CollectSiblingLists collectSiblingLists = new CollectSiblingLists();
		for (InputDocument doc : documents) {
			collectSiblingLists.setTokens(doc.tokens, doc.tree);
			ParseTreeWalker.DEFAULT.walk(collectTokenDependencies, doc.tree);
			ParseTreeWalker.DEFAULT.walk(collectSiblingLists, doc.tree);
		}
		Map<String, List<Pair<Integer, Integer>>> ruleToPairsBag = collectTokenDependencies.getDependencies();
		Map<ParentSiblingListKey, SiblingListStats> rootAndChildListStats =
			collectSiblingLists.getListStats();
		Map<ParentSiblingListKey, SiblingListStats> rootAndSplitChildListStats =
			collectSiblingLists.getSplitListStats();
		Map<ParentSiblingListKey, Integer> splitListForms = collectSiblingLists.getSplitListForms();
		Map<Token, Pair<Boolean, Integer>> tokenToListInfo = collectSiblingLists.getTokenToListInfo();

		if ( false ) {
			for (String ruleName : ruleToPairsBag.keySet()) {
				List<Pair<Integer, Integer>> pairs = ruleToPairsBag.get(ruleName);
				System.out.print(ruleName+": ");
				for (Pair<Integer, Integer> p : pairs) {
					System.out.print(vocab.getDisplayName(p.a)+","+vocab.getDisplayName(p.b)+" ");
				}
				System.out.println();
			}
		}

		if ( false ) {
			for (ParentSiblingListKey siblingPairs : rootAndChildListStats.keySet()) {
				String parent = ruleNames[siblingPairs.parentRuleIndex];
				parent = parent.replace("Context","");
				String siblingListName = ruleNames[siblingPairs.childRuleIndex];
				siblingListName = siblingListName.replace("Context","");
				System.out.println(parent+":"+siblingPairs.parentRuleAlt+"->"+siblingListName+":"+siblingPairs.childRuleAlt+
					                   " (min,median,var,max)="+rootAndChildListStats.get(siblingPairs));
			}
			for (ParentSiblingListKey siblingPairs : rootAndSplitChildListStats.keySet()) {
				String parent = ruleNames[siblingPairs.parentRuleIndex];
				parent = parent.replace("Context","");
				String siblingListName = ruleNames[siblingPairs.childRuleIndex];
				siblingListName = siblingListName.replace("Context","");
				System.out.println("SPLIT " +parent+":"+siblingPairs.parentRuleAlt+"->"+siblingListName+":"+siblingPairs.childRuleAlt+
					                   " (min,median,var,max)="+rootAndSplitChildListStats.get(siblingPairs)+
				                  " form "+splitListForms.get(siblingPairs));
			}
		}

		Corpus corpus = processSampleDocs(documents, ruleToPairsBag,
		                                  rootAndChildListStats, rootAndSplitChildListStats,
		                                  splitListForms, tokenToListInfo);
		if ( shuffleFeatureVectors ) corpus.randomShuffleInPlace();
		corpus.buildTokenContextIndex();
		return corpus;
	}

	public static Corpus processSampleDocs(List<InputDocument> docs,
										   Map<String, List<Pair<Integer, Integer>>> ruleToPairsBag,
										   Map<ParentSiblingListKey, SiblingListStats> rootAndChildListStats,
										   Map<ParentSiblingListKey, SiblingListStats> rootAndSplitChildListStats,
										   Map<ParentSiblingListKey, Integer> splitListForms,
										   Map<Token, Pair<Boolean, Integer>> tokenToListInfo)
		throws Exception
	{
		List<InputDocument> documents = new ArrayList<>();
		List<int[]> featureVectors = new ArrayList<>();
		List<Integer> injectNewlines = new ArrayList<>();
		List<Integer> alignWithPrevious = new ArrayList<>();
		Corpus corpus = new Corpus(documents, featureVectors, injectNewlines, alignWithPrevious);
		corpus.ruleToPairsBag = ruleToPairsBag;
		corpus.rootAndChildListStats = rootAndChildListStats;
		corpus.rootAndSplitChildListStats = rootAndSplitChildListStats;
		corpus.splitListForms = splitListForms;
		corpus.tokenToListInfo = tokenToListInfo;

		for (InputDocument doc : docs) {
			if ( showFileNames ) System.out.println(doc);
			doc.corpus = corpus; // we know the corpus object now
			process(doc);

			for (int i=0; i<doc.featureVectors.size(); i++) {
				documents.add(doc);
				int[] featureVec = doc.featureVectors.get(i);
				injectNewlines.add(doc.injectWhitespace.get(i));
				alignWithPrevious.add(doc.align.get(i));
				featureVectors.add(featureVec);
			}
		}
		System.out.printf("%d feature vectors\n", featureVectors.size());
		return corpus;
	}

	/** Parse document, save feature vectors to the doc */
	public static void process(InputDocument doc) {
		Trainer trainer = new Trainer(doc);
		trainer.computeFeatureVectors();

		doc.featureVectors = trainer.getFeatureVectors();
		doc.injectWhitespace = trainer.getInjectWhitespace();
		doc.align = trainer.getAlign();
	}

	public static CommonTokenStream tokenize(String doc, Class<? extends Lexer> lexerClass)
		throws Exception
	{
		ANTLRInputStream input = new ANTLRInputStream(doc);
		Lexer lexer = getLexer(lexerClass, input);

		CommonTokenStream tokens = new CodeBuffTokenStream(lexer);
		tokens.fill();
		return tokens;
	}

	/** Parse doc and fill tree and tokens fields */
	public static void parse(InputDocument doc,
							 Class<? extends Lexer> lexerClass,
							 Class<? extends Parser> parserClass,
							 String startRuleName)
		throws Exception
	{
		ANTLRInputStream input = new ANTLRInputStream(doc.content);
		Lexer lexer = getLexer(lexerClass, input);
		input.name = doc.fileName;

		CodeBuffTokenStream tokens = new CodeBuffTokenStream(lexer);

		if ( showTokens ) {
			tokens.fill();
			for (Object tok : tokens.getTokens()) {
				System.out.println(tok);
			}
		}

		doc.parser = getParser(parserClass, tokens);
		doc.parser.setBuildParseTree(true);
		Method startRule = parserClass.getMethod(startRuleName);
		ParserRuleContext tree = (ParserRuleContext)startRule.invoke(doc.parser, (Object[]) null);

		doc.tokens = tokens;
		doc.tree = tree;
	}

	public static Parser getParser(Class<? extends Parser> parserClass, CommonTokenStream tokens) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException {
		Constructor<? extends Parser> parserCtor =
			parserClass.getConstructor(TokenStream.class);
		return parserCtor.newInstance(tokens);
	}

	public static Lexer getLexer(Class<? extends Lexer> lexerClass, ANTLRInputStream input) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException {
		Constructor<? extends Lexer> lexerCtor =
			lexerClass.getConstructor(CharStream.class);
		return lexerCtor.newInstance(input);
	}

	/** Get all file contents into input doc list */
	public static List<InputDocument> load(List<String> fileNames, int tabSize)
		throws Exception
	{
		List<InputDocument> input = new ArrayList<>(fileNames.size());
		int i = 0;
		for (String f : fileNames) {
			InputDocument doc = load(f, tabSize);
			doc.index = i++;
			input.add(doc);
		}
		System.out.println(input.size()+" files");
		return input;
	}

	public static InputDocument load(String fileName, int tabSize)
		throws Exception
	{
		Path path = FileSystems.getDefault().getPath(fileName);
		byte[] filearray = Files.readAllBytes(path);
		String content = new String(filearray);
		String notabs = expandTabs(content, tabSize);
		return new InputDocument(null, fileName, notabs);
	}


	/** From input documents, grab n in random order w/o replacement */
	public List<InputDocument> getRandomDocuments(List<InputDocument> documents, int n) {
		final Random random = new Random();
		random.setSeed(DOCLIST_RANDOM_SEED);
		List<InputDocument> documents_ = new ArrayList<>(documents);
		Collections.shuffle(documents_, random);
		List<InputDocument> contentList = new ArrayList<>(n);
		for (int i=0; i<n; i++) { // get first n files from shuffle and set file index for it
			contentList.add(documents.get(i));
		}
		return contentList;
	}

	/** From input documents, grab n in random order w replacement */
	public List<InputDocument> getRandomDocumentsWithRepl(List<InputDocument> documents, int n) {
		final Random random = new Random();
		random.setSeed(DOCLIST_RANDOM_SEED);
		List<InputDocument> contentList = new ArrayList<>(n);
		for (int i=1; i<=n; i++) {
			int r = random.nextInt(documents.size()); // get random index from 0..|inputfiles|-1
			contentList.add(documents.get(r));
		}
		return contentList;
	}

	public static List<String> getFilenames(File f, String inputFilePattern) throws Exception {
		List<String> files = new ArrayList<>();
		getFilenames_(f, inputFilePattern, files);
		return files;
	}

	public static void getFilenames_(File f, String inputFilePattern, List<String> files) {
		// If this is a directory, walk each file/dir in that directory
		if (f.isDirectory()) {
			String flist[] = f.list();
			for (String aFlist : flist) {
				getFilenames_(new File(f, aFlist), inputFilePattern, files);
			}
		}

		// otherwise, if this is an input file, load it!
		else if ( inputFilePattern==null || f.getName().matches(inputFilePattern) ) {
		  	files.add(f.getAbsolutePath());
		}
	}

	public static String join(int[] array, String separator) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < array.length; i++) {
			builder.append(array[i]);
			if (i < array.length - 1) {
				builder.append(separator);
			}
		}

		return builder.toString();
	}

	public static String join(String[] array, String separator) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < array.length; i++) {
			builder.append(array[i]);
			if (i < array.length - 1) {
				builder.append(separator);
			}
		}

		return builder.toString();
	}

	public static List<CommonToken> copy(CommonTokenStream tokens) {
		List<CommonToken> copy = new ArrayList<>();
		tokens.fill();
		for (Token t : tokens.getTokens()) {
			copy.add(new CommonToken(t));
		}
		return copy;
	}

	public static int L0_Distance(boolean[] categorical, int[] A, int[] B) {
		int count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			if ( categorical[i] ) {
				if ( A[i] != B[i] ) {
					count++;
				}
			}
		}
		return count;
	}

	/** A distance of 0 should count much more than non-0. Also, penalize
	 *  mismatches closer to current token than those farther away.
	 */
	public static double weightedL0_Distance(FeatureMetaData[] featureTypes, int[] A, int[] B) {
		double count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			FeatureType type = featureTypes[i].type;
			if ( type==FeatureType.TOKEN ||
				 type==FeatureType.RULE ||
				 type==FeatureType.INT ||
				 type==FeatureType.BOOL)
			{
				if ( A[i] != B[i] ) {
					count += featureTypes[i].mismatchCost;
				}
			}
			else if ( type==FeatureType.COLWIDTH ) {
				// threshold any len > RIGHT_MARGIN_ALARM
				int a = A[i];
				int b = B[i];
//				int a = Math.min(A[i], WIDE_LIST_THRESHOLD);
//				int b = Math.min(B[i], WIDE_LIST_THRESHOLD);
//				count += Math.abs(a-b) / (float) WIDE_LIST_THRESHOLD; // normalize to 0..1
//				count += sigmoid(a-b, 37);
				double delta = Math.abs(sigmoid(a, 43)-sigmoid(b, 43));
				count += delta;
			}
		}
		return count;
	}

	public static double sigmoid(int x, float center) {
		return 1.0 / (1.0 + Math.exp(-0.9*(x-center)));
	}

	public static int max(List<Integer> Y) {
		int max = 0;
		for (int y : Y) max = Math.max(max, y);
		return max;
	}

	public static int sum(int[] a) {
		int s = 0;
		for (int x : a) s += x;
		return s;
	}

	/** from https://en.wikipedia.org/wiki/Levenshtein_distance
	 *  "It is always at least the difference of the sizes of the two strings."
	 *  "It is at most the length of the longer string."
	 */
	public static float levenshteinDistance(String s, String t) {
	    // degenerate cases
	    if (s.equals(t)) return 0;
	    if (s.length() == 0) return t.length();
	    if (t.length() == 0) return s.length();

	    // create two work vectors of integer distances
	    int[] v0 = new int[t.length() + 1];
	    int[] v1 = new int[t.length() + 1];

	    // initialize v0 (the previous row of distances)
	    // this row is A[0][i]: edit distance for an empty s
	    // the distance is just the number of characters to delete from t
	    for (int i = 0; i < v0.length; i++) {
			v0[i] = i;
		}

	    for (int i = 0; i < s.length(); i++) {
	        // calculate v1 (current row distances) from the previous row v0

	        // first element of v1 is A[i+1][0]
	        //   edit distance is delete (i+1) chars from s to match empty t
	        v1[0] = i + 1;

	        // use formula to fill in the rest of the row
	        for (int j = 0; j < t.length(); j++)
	        {
	            int cost = s.charAt(i) == t.charAt(j) ? 0 : 1;
	            v1[j + 1] = Math.min(
								Math.min(v1[j] + 1, v0[j + 1] + 1),
								v0[j] + cost);
	        }

	        // copy v1 (current row) to v0 (previous row) for next iteration
			System.arraycopy(v1, 0, v0, 0, v0.length);
	    }

	    int d = v1[t.length()];
		int min = Math.abs(s.length()-t.length());
		int max = Math.max(s.length(), t.length());
		return (d-min) / (float)max;
	}

	/* Compare whitespace and give an approximate Levenshtein distance /
	   edit distance. MUCH faster to use this than pure Levenshtein which
	   must consider all of the "real" text that is in common.

		when only 1 kind of char, just substract lengths
		Orig    Altered Distance
		AB      A B     1
		AB      A  B    2
		AB      A   B   3
		A B     A  B    1

		A B     AB      1
		A  B    AB      2
		A   B   AB      3

		when ' ' and '\n', we count separately.

		A\nB    A B     spaces delta=1, newline delete=1, distance = 2
		A\nB    A  B    spaces delta=2, newline delete=1, distance = 3
		A\n\nB  A B     spaces delta=1, newline delete=2, distance = 3
		A\n \nB A B     spaces delta=0, newline delete=2, distance = 2
		A\n \nB A\nB    spaces delta=1, newline delete=1, distance = 2
		A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
//	public static int editDistance(String s, String t) {
//	}

	/*
			A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
	public static int whitespaceEditDistance(String s, String t) {
		int s_spaces = count(s, ' ');
		int s_nls = count(s, '\n');
		int t_spaces = count(t, ' ');
		int t_nls = count(t, '\n');
		return Math.abs(s_spaces - t_spaces) + Math.abs(s_nls - t_nls);
	}

	/** Compute a document difference metric 0-1.0 between two documents that
	 *  are identical other than (likely) the whitespace and comments.
	 *
	 *  1.0 means the docs are maximally different and 0 means docs are identical.
	 *
	 *  The Levenshtein distance between the docs counts only
	 *  whitespace diffs as the non-WS content is identical.
	 *  Levenshtein distance is bounded by 0..max(len(doc1),len(doc2)) so
	 *  we normalize the distance by dividing by max WS count.
	 *
	 *  TODO: can we simplify this to a simple walk with two
	 *  cursors through the original vs formatted counting
	 *  mismatched whitespace? real text are like anchors.
	 */
	public static double docDiff(String original,
	                             String formatted,
	                             Class<? extends Lexer> lexerClass)
		throws Exception
	{
		// Grammar must strip all but real tokens and whitespace (and put that on hidden channel)
		CommonTokenStream original_tokens = tokenize(original, lexerClass);
//		String s = original_tokens.getText();
		CommonTokenStream formatted_tokens = tokenize(formatted, lexerClass);
//		String t = formatted_tokens.getText();

		// walk token streams and examine whitespace in between tokens
		int i = 1;
		int ws_distance = 0;
		int original_ws = 0;
		int formatted_ws = 0;
		while ( true ) {
			Token ot = original_tokens.LT(i);
			if ( ot==null || ot.getType()==Token.EOF ) break;
			List<Token> ows = original_tokens.getHiddenTokensToLeft(ot.getTokenIndex());
			original_ws += tokenText(ows).length();

			Token ft = formatted_tokens.LT(i);
			if ( ft==null || ft.getType()==Token.EOF ) break;
			List<Token> fws = formatted_tokens.getHiddenTokensToLeft(ft.getTokenIndex());
			formatted_ws += tokenText(fws).length();

			ws_distance += whitespaceEditDistance(tokenText(ows), tokenText(fws));
			i++;
		}
		// it's probably ok to ignore ws diffs after last real token

//		int non_ws = 0;
//		for (Token tok : original_tokens.getTokens()) {
//			if ( tok.getType()!=Token.EOF && tok.getChannel()==Lexer.DEFAULT_TOKEN_CHANNEL ) {
//				non_ws += tok.getText().length();
//			}
//		}
//		String original_text_with_ws = original_tokens.getText();
//		int original_ws = original_text_with_ws.length() - non_ws;
//		int formatted_ws = formatted.length() - non_ws;
//		int ws_distance = Tool.levenshteinDistance(original_text_with_ws, formatted);
		int max_ws = Math.max(original_ws, formatted_ws);
		double normalized_ws_distance = ((float) ws_distance)/max_ws;
		return normalized_ws_distance;
	}

	/** Compare an input document's original text with its formatted output
	 *  and return the ratio of the incorrectWhiteSpaceCount to total whitespace
	 *  count in the original document text. It is a measure of document
	 *  similarity.
	 */
	public static double compare(InputDocument doc,
	                             String formatted,
	                             Class<? extends Lexer> lexerClass)
		throws Exception
	{
		doc.allWhiteSpaceCount = 0;
		doc.incorrectWhiteSpaceCount = 0;

		String original = doc.content;

		// Grammar must strip all but real tokens and whitespace (and put that on hidden channel)
		CommonTokenStream original_tokens = tokenize(original, lexerClass);
		CommonTokenStream formatted_tokens = tokenize(formatted, lexerClass);

		// walk token streams and examine whitespace in between tokens
		int i = 1;

		while ( true ) {
			Token ot = original_tokens.LT(i);
			if ( ot==null || ot.getType()==Token.EOF ) break;
			List<Token> ows = original_tokens.getHiddenTokensToLeft(ot.getTokenIndex());
			String original_ws = tokenText(ows);

			Token ft = formatted_tokens.LT(i);
			if ( ft==null || ft.getType()==Token.EOF ) break;
			List<Token> fws = formatted_tokens.getHiddenTokensToLeft(ft.getTokenIndex());
			String formatted_ws = tokenText(fws);

			if (original_ws.length() == 0) {
				if (formatted_ws.length() != 0) {
					doc.incorrectWhiteSpaceCount++;

					if (doc.dumpIncorrectWS) {
						System.out.printf("\n*** Extra WS - line %d:\n", ot.getLine());
						Tool.printOriginalFilePiece(doc, (CommonToken)ot);
						System.out.println("actual: " + Tool.dumpWhiteSpace(formatted_ws));
					}
				}
			}
			else {
				doc.allWhiteSpaceCount++;

				if (formatted_ws.length() == 0) {
					doc.incorrectWhiteSpaceCount++;

					if (doc.dumpIncorrectWS) {
						System.out.printf("\n*** Miss a WS - line %d:\n", ot.getLine());
						Tool.printOriginalFilePiece(doc, (CommonToken) ot);
						System.out.println("should: " + Tool.dumpWhiteSpace(original_ws));
					}
				}
				else if (!TwoWSEqual(original_ws, formatted_ws)) {
					doc.incorrectWhiteSpaceCount++;

					if (doc.dumpIncorrectWS) {
						System.out.printf("\n*** Incorrect WS - line %d:\n", ot.getLine());
						Tool.printOriginalFilePiece(doc, (CommonToken)ot);
						System.out.println("should: " + Tool.dumpWhiteSpace(original_ws));
						System.out.println("actual: " + Tool.dumpWhiteSpace(formatted_ws));
					}
				}
			}

			i++;
		}
		return ((double)doc.incorrectWhiteSpaceCount) / doc.allWhiteSpaceCount;
	}


	// it's a compare function but only focus on NL
	// basically this function is copy and paste from compare function on above
	public static double compareNL(InputDocument doc,
								 String formatted,
								 Class<? extends Lexer> lexerClass)
		throws Exception
	{
		doc.allWhiteSpaceCount = 0;
		doc.incorrectWhiteSpaceCount = 0;

		String original = doc.content;

		// Grammar must strip all but real tokens and whitespace (and put that on hidden channel)
		CommonTokenStream original_tokens = tokenize(original, lexerClass);
		CommonTokenStream formatted_tokens = tokenize(formatted, lexerClass);

		// walk token streams and examine whitespace in between tokens
		int i = 1;

		while ( true ) {
			Token ot = original_tokens.LT(i);
			if ( ot==null || ot.getType()==Token.EOF ) break;
			List<Token> ows = original_tokens.getHiddenTokensToLeft(ot.getTokenIndex());
			String original_ws = tokenText(ows);

			Token ft = formatted_tokens.LT(i);
			if ( ft==null || ft.getType()==Token.EOF ) break;
			List<Token> fws = formatted_tokens.getHiddenTokensToLeft(ft.getTokenIndex());
			String formatted_ws = tokenText(fws);

			if (original_ws.length() == 0) {
				if (formatted_ws.length() != 0) {
					if (count(formatted_ws, '\n') > 0) {
						doc.incorrectWhiteSpaceCount++;

						if (doc.dumpIncorrectWS) {
							System.out.printf("\n*** Extra WS - line %d:\n", ot.getLine());
							Tool.printOriginalFilePiece(doc, (CommonToken)ot);
							System.out.println("actual: " + Tool.dumpWhiteSpace(formatted_ws));
						}
					}
				}
			}
			else {
				if (count(original_ws, '\n') > 0) {
					doc.allWhiteSpaceCount++;

					if (formatted_ws.length() == 0) {
						doc.incorrectWhiteSpaceCount++;

						if (doc.dumpIncorrectWS) {
							System.out.printf("\n*** Miss a WS - line %d:\n", ot.getLine());
							Tool.printOriginalFilePiece(doc, (CommonToken) ot);
							System.out.println("should: " + Tool.dumpWhiteSpace(original_ws));
						}
					}
					else if (count(original_ws, '\n') != count(formatted_ws, '\n')) {
						doc.incorrectWhiteSpaceCount++;

						if (doc.dumpIncorrectWS) {
							System.out.printf("\n*** Incorrect WS - line %d:\n", ot.getLine());
							Tool.printOriginalFilePiece(doc, (CommonToken)ot);
							System.out.println("should: " + Tool.dumpWhiteSpace(original_ws));
							System.out.println("actual: " + Tool.dumpWhiteSpace(formatted_ws));
						}
					}
				}
			}

			i++;
		}
		return ((double)doc.incorrectWhiteSpaceCount) / doc.allWhiteSpaceCount;
	}

	public static String tokenText(List<Token> tokens) {
		if ( tokens==null ) return "";
		StringBuilder buf = new StringBuilder();
		for (Token t : tokens) {
			buf.append(t.getText());
		}
		return buf.toString();
	}

	public static int getNumberRealTokens(CommonTokenStream tokens, int from, int to) {
		if ( tokens==null ) return 0;
		int n = 0;
		if ( from<0 ) from = 0;
		if ( to>tokens.size() ) to = tokens.size()-1;
		for (int i = from; i <= to; i++) {
			Token t = tokens.get(i);
			if ( t.getChannel()==Token.DEFAULT_CHANNEL ) {
				n++;
			}
		}
		return n;
	}

	public static String spaces(int n) {
		return sequence(n, " ");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append(" ");
//		return buf.toString();
	}

	public static String newlines(int n) {
		return sequence(n, "\n");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append("\n");
//		return buf.toString();
	}

	public static String sequence(int n, String s) {
		StringBuilder buf = new StringBuilder();
		for (int sp=1; sp<=n; sp++) buf.append(s);
		return buf.toString();
	}

	public static int count(String s, char x) {
		int n = 0;
		for (int i = 0; i<s.length(); i++) {
			if ( s.charAt(i)==x ) {
				n++;
			}
		}
		return n;
	}

	public static String expandTabs(String s, int tabSize) {
		if ( s==null ) return null;
		StringBuilder buf = new StringBuilder();
		int col = 0;
		for (int i = 0; i<s.length(); i++) {
			char c = s.charAt(i);
			switch ( c ) {
				case '\n' :
					col = 0;
					buf.append(c);
					break;
				case '\t' :
					int n = tabSize-col%tabSize;
					col+=n;
					buf.append(spaces(n));
					break;
				default :
					col++;
					buf.append(c);
					break;
			}
		}
		return buf.toString();
	}

	public static String dumpWhiteSpace(String s) {
		String[] whiteSpaces = new String[s.length()];
		for (int i=0; i<s.length(); i++) {
			char c = s.charAt(i);
			switch ( c ) {
				case '\n' :
					whiteSpaces[i] = "\\n";
					break;
				case '\t' :
					whiteSpaces[i] = "\\t";
					break;
				case '\r' :
					whiteSpaces[i] = "\\r";
					break;
				case '\u000C' :
					whiteSpaces[i] = "\\u000C";
					break;
				case ' ' :
					whiteSpaces[i] = "ws";
					break;
				default :
					whiteSpaces[i] = String.valueOf(c);
					break;
			}
		}
		return join(whiteSpaces, " | ");
	}

	// In some case, before a new line sign, there maybe some white space.
	// But those white spaces won't change the look of file.
	// To compare if two WS are the same, we should remove all the shite space before the first '\n'
	public static boolean TwoWSEqual(String a, String b) {
		String newA = a;
		String newB = b;

		int aStartNLIndex = a.indexOf('\n');
		int bStartNLIndex = b.indexOf('\n');

		if (aStartNLIndex > 0) newA = a.substring(aStartNLIndex);
		if (bStartNLIndex > 0) newB = b.substring(bStartNLIndex);

		return newA.equals(newB);
	}

	public static void printOriginalFilePiece(InputDocument doc, CommonToken originalCurToken) {
		System.out.println(doc.getLine(originalCurToken.getLine()-1));
		System.out.println(doc.getLine(originalCurToken.getLine()));
		System.out.print(Tool.spaces(originalCurToken.getCharPositionInLine()));
		System.out.println("^");
	}


	/** Given a corpus, format the given input documents and compute their document
	 *  similarities with {@link #compare}.
	 */
	public static ArrayList<Double> validateResults(Corpus corpus, List<InputDocument> testDocs,
	                                                Class<? extends Lexer> lexerClass,
	                                                Class<? extends Parser> parserClass,
	                                                String startRuleName,
	                                                int tabSize)
		throws Exception
	{
		ArrayList<Double> differenceRatios = new ArrayList<>();

		for (InputDocument testDoc: testDocs) {
			Pair<String, List<TokenPositionAnalysis>> results =
				format(corpus, testDoc, lexerClass, parserClass, startRuleName, tabSize, false);
			String formattedDoc = results.a;
			boolean dumpIncorrectWSOldValue = testDoc.dumpIncorrectWS;
			testDoc.dumpIncorrectWS = false;
			double differenceRatio = compareNL(testDoc, formattedDoc, lexerClass);
			testDoc.dumpIncorrectWS = dumpIncorrectWSOldValue;
			differenceRatios.add(differenceRatio);
		}
		return differenceRatios;
	}

	// return the median value of validate results array
	public static double validate(Corpus corpus, List<InputDocument> testDocs,
	                              Class<? extends Lexer> lexerClass,
	                              Class<? extends Parser> parserClass,
	                              String startRuleName,
	                              int tabSize)
		throws Exception
	{
		ArrayList<Double> differenceRatios =
			validateResults(corpus, testDocs, lexerClass, parserClass, startRuleName, tabSize);
		Collections.sort(differenceRatios);
		if (differenceRatios.size() % 2 == 1) return differenceRatios.get(differenceRatios.size() / 2);
		else if (differenceRatios.size() == 0) {
			System.err.println("Don't have enough results to get median value from validate results array!");
			return -1;
		}
		else return (differenceRatios.get(differenceRatios.size() / 2) + differenceRatios.get(differenceRatios.size() / 2 - 1))/2;
	}


	public static class Foo {
		public static void main(String[] args) throws Exception {
			ANTLRv4Lexer lexer = new ANTLRv4Lexer(new ANTLRFileStream("grammars/org/antlr/codebuff/ANTLRv4Lexer.g4"));
			CommonTokenStream tokens = new CodeBuffTokenStream(lexer);
			ANTLRv4Parser parser = new ANTLRv4Parser(tokens);
			ANTLRv4Parser.GrammarSpecContext tree = parser.grammarSpec();
			System.out.println(tree.toStringTree(parser));
		}
	}
}