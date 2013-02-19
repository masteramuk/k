package org.kframework.krun.api;

import org.apache.commons.collections15.Transformer;
import org.kframework.backend.maude.MaudeFilter;
import org.kframework.backend.unparser.UnparserFilter;
import org.kframework.compile.transformers.FlattenSyntax;
import org.kframework.compile.utils.MetaK;
import org.kframework.kil.*;
import org.kframework.kil.loader.DefinitionHelper;
import org.kframework.krun.runner.KRunner;
import org.kframework.krun.*;
import org.kframework.krun.api.Transition.TransitionType;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.general.GlobalSettings;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.uci.ics.jung.graph.*;
import edu.uci.ics.jung.io.graphml.*;

import java.io.File;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MaudeKRun implements KRun {
	private void executeKRun(String maudeCmd, boolean ioServer) throws Exception {
		FileUtil.createFile(K.maude_in, maudeCmd);
		File outFile = FileUtil.createFile(K.maude_out);
		File errFile = FileUtil.createFile(K.maude_err);

		if (K.log_io) {
			KRunner.main(new String[] { "--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath(), "--createLogs" });
		}
		if (!ioServer) {
			KRunner.main(new String[] { "--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath(), "--noServer" });
		} else {
			KRunner.main(new String[] { "--maudeFile", K.compiled_def + K.fileSeparator + "main.maude", "--moduleName", K.main_module, "--commandFile", K.maude_in, "--outputFile", outFile.getCanonicalPath(), "--errorFile", errFile.getCanonicalPath() });
		}

		if (errFile.exists()) {
			String content = FileUtil.getFileContent(K.maude_err);
			if (content.length() > 0) {
				throw new KRunExecutionException(content);
			}
		}

	}

	public KRunResult<KRunState> run(Term cfg) throws Exception {
		MaudeFilter maudeFilter = new MaudeFilter();
		cfg.accept(maudeFilter);
		String cmd = "set show command off ." + K.lineSeparator + setCounter() + K.maude_cmd + " " + maudeFilter.getResult() + " ." + getCounter();
		if(K.trace) {
			cmd = "set trace on ." + K.lineSeparator + cmd;
		}
		if(K.profile) {
			cmd = "set profile on ." + K.lineSeparator + cmd + K.lineSeparator + "show profile .";
		}

		executeKRun(cmd, K.io);
		return parseRunResult();
	}

	private String setCounter() {
		return "red setCounter(" + Integer.toString(K.counter) + ") ." + K.lineSeparator;
	}

	private String getCounter() {
		return K.lineSeparator + "red counter .";
	}

	public KRunResult<KRunState> step(Term cfg, int steps) throws Exception {
		String maude_cmd = K.maude_cmd;
		if (steps == 0) {
			K.maude_cmd = "red";
		} else {
			K.maude_cmd = "rew[" + Integer.toString(steps) + "]";
		}
		KRunResult<KRunState> result = run(cfg);
		K.maude_cmd = maude_cmd;
		return result;
	}

	//needed for --statistics command
	private String printStatistics(Element elem) {
		String result = "";
		if ("search".equals(K.maude_cmd)) {
			String totalStates = elem.getAttribute("total-states");
			String totalRewrites = elem.getAttribute("total-rewrites");
			String realTime = elem.getAttribute("real-time-ms");
			String cpuTime = elem.getAttribute("cpu-time-ms");
			String rewritesPerSecond = elem.getAttribute("rewrites-per-second");
			result += "states: " + totalStates + " rewrites: " + totalRewrites + " in " + cpuTime + "ms cpu (" + realTime + "ms real) (" + rewritesPerSecond + " rewrites/second)";
		} else if ("erewrite".equals(K.maude_cmd)){
			String totalRewrites = elem.getAttribute("total-rewrites");
			String realTime = elem.getAttribute("real-time-ms");
			String cpuTime = elem.getAttribute("cpu-time-ms");
			String rewritesPerSecond = elem.getAttribute("rewrites-per-second");
			result += "rewrites: " + totalRewrites + " in " + cpuTime + "ms cpu (" + realTime + "ms real) (" + rewritesPerSecond + " rewrites/second)";
		}
		return result;
	}



	private KRunResult<KRunState> parseRunResult() throws Exception {
		File input = new File(K.maude_output);
		Document doc = XmlUtil.readXML(input);
		NodeList list = null;
		Node nod = null;
		list = doc.getElementsByTagName("result");
		nod = list.item(1);

		assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
		Element elem = (Element) nod;
		List<Element> child = XmlUtil.getChildElements(elem);
		assertXML(child.size() == 1);

		KRunState state = parseElement((Element) child.get(0));
		KRunResult<KRunState> ret = new KRunResult<KRunState>(state);
		String statistics = printStatistics(elem);
		ret.setStatistics(statistics);
		ret.setRawOutput(FileUtil.getFileContent(K.maude_out));
		parseCounter(list.item(2));
		return ret;
	}

	private KRunState parseElement(Element el) {
		Term rawResult = MaudeKRun.parseXML(el);

		return new KRunState(rawResult);
	}

	private void parseCounter(Node counter) throws Exception {
		assertXML(counter != null && counter.getNodeType() == Node.ELEMENT_NODE);
		Element elem = (Element) counter;
		List<Element> child = XmlUtil.getChildElements(elem);
		assertXML(child.size() == 1);
		Term t = parseXML(child.get(0));
		K.counter = Integer.parseInt(((Constant)t).getValue()) - 1;
	}

	private static void assertXML(boolean assertion) {
		if (!assertion) {
			GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, "Cannot parse result xml from maude. If you believe this to be in error, please file a bug and attach " + K.maude_output.replaceAll("/krun[0-9]*/", "/krun/")));
		}
	}

	private static void assertXMLTerm(boolean assertion) throws Exception {
		if (!assertion) {
			throw new Exception();
		}
	}

	public static Term parseXML(Element xml) {
		String op = xml.getAttribute("op");
		String sort = xml.getAttribute("sort");
		sort = sort.replaceAll("`([{}\\[\\](),])", "$1");
		List<Element> list = XmlUtil.getChildElements(xml);
		
		try {
			if (sort.equals("BagItem") && op.equals("<_>_</_>")) {
				Cell cell = new Cell();
				assertXMLTerm(list.size() == 3 && list.get(0).getAttribute("sort").equals("CellLabel") && list.get(2).getAttribute("sort").equals("CellLabel") && list.get(0).getAttribute("op").equals(list.get(2).getAttribute("op")));

				cell.setLabel(list.get(0).getAttribute("op"));
				cell.setContents(parseXML(list.get(1)));
				return cell;
			} else if (sort.equals("BagItem") && op.equals("BagItem")) {
				assertXMLTerm(list.size() == 1);
				return new BagItem(parseXML(list.get(0)));
			} else if (sort.equals("MapItem") && op.equals("_|->_")) {
				assertXMLTerm(list.size() == 2);
				return new MapItem(parseXML(list.get(0)), parseXML(list.get(1)));
			} else if (sort.equals("SetItem") && op.equals("SetItem")) {
				assertXMLTerm(list.size() == 1);
				return new SetItem(parseXML(list.get(0)));
			} else if (sort.equals("ListItem") && op.equals("ListItem")) {
				assertXMLTerm(list.size() == 1);
				return new ListItem(parseXML(list.get(0)));
			} else if (op.equals("_`,`,_") && sort.equals("NeKList")) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new KList(l);
			} else if (sort.equals("K") && op.equals("_~>_")) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new KSequence(l);
			} else if (op.equals("__") && (sort.equals("NeList") || sort.equals("List"))) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new org.kframework.kil.List(l);
			} else if (op.equals("__") && (sort.equals("NeBag") || sort.equals("Bag"))) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new Bag(l);
			} else if (op.equals("__") && (sort.equals("NeSet") || sort.equals("Set"))) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new org.kframework.kil.Set(l);
			} else if (op.equals("__") && (sort.equals("NeMap") || sort.equals("Map"))) {
				assertXMLTerm(list.size() >= 2);
				List<Term> l = new ArrayList<Term>();
				for (Element elem : list) {
					l.add(parseXML(elem));
				}
				return new org.kframework.kil.Map(l);
			} else if ((op.equals("#_") || op.equals("List2KLabel_") || op.equals("Map2KLabel_") || op.equals("Set2KLabel_") || op.equals("Bag2KLabel_") || op.equals("KList2KLabel_") || op.equals("KLabel2KLabel_")) && sort.equals("KLabel")) {
				assertXMLTerm(list.size() == 1);
				return new KInjectedLabel(parseXML(list.get(0)));
			} else if (sort.equals("#NzInt") && op.equals("--Int_")) {
				assertXMLTerm(list.size() == 1);
				return new Constant("#Int", "-" + ((Constant) parseXML(list.get(0))).getValue());
			} else if (sort.equals("#NzNat") && op.equals("sNat_")) {
				assertXMLTerm(list.size() == 1 && ((Constant) parseXML(list.get(0))).getValue().equals("0"));
				return new Constant("#Int", xml.getAttribute("number"));
			} else if (sort.equals("#Zero") && op.equals("0")) {
				assertXMLTerm(list.size() == 0);
				return new Constant("#Int", "0");
			} else if (sort.equals("#Bool") && (op.equals("true") || op.equals("false"))) {
				assertXMLTerm(list.size() == 0);
				return new Constant("#Bool", op);
			} else if (sort.equals("#Char") || sort.equals("#String")) {
				assertXMLTerm(list.size() == 0);
				return new Constant("#String", op);
			} else if (sort.equals("#FiniteFloat")) {
				assertXMLTerm(list.size() == 0);
				return new Constant("#Float", op);
			} else if (sort.equals("#Id") && op.equals("#id_")) {
				assertXMLTerm(list.size() == 1);
				String value = ((Constant) parseXML(list.get(0))).getValue();
				assertXMLTerm(value.startsWith("\"") && value.endsWith("\""));
				return new Constant("#Id", value.substring(1,value.length()-1));
			} else if (op.equals(".") && (sort.equals("Bag") || sort.equals("List") || sort.equals("Map") || sort.equals("Set") || sort.equals("K"))) {
				assertXMLTerm(list.size() == 0);
				return new Empty(sort);
			} else if (op.equals(".KList") && sort.equals(MetaK.Constants.KList)) {
				assertXMLTerm(list.size() == 0);
				return new Empty(MetaK.Constants.KList);
			} else if (op.equals("_`(_`)") && sort.equals("KItem")) {
				assertXMLTerm(list.size() == 2);
				return new KApp(parseXML(list.get(0)), parseXML(list.get(1)));
			} else if (sort.equals("KLabel") && list.size() == 0) {
				return new Constant("KLabel", op);
			} else if (sort.equals("KLabel") && op.equals("#freezer_")) {
				assertXMLTerm(list.size() == 1);
				return new FreezerLabel(parseXML(list.get(0)));	
			} else if (op.equals("HOLE")) {
				assertXMLTerm(list.size() == 0);
				return new Hole(sort);
			} else {
				Set<String> conses = DefinitionHelper.labels.get(op);
				Set<String> validConses = new HashSet<String>();
				List<Term> possibleTerms = new ArrayList<Term>();
				assertXMLTerm(conses != null);
				for (String cons : conses) {
					Production p = DefinitionHelper.conses.get(cons);
					if (p.getSort().equals(sort) && p.getArity() == list.size()) {
						validConses.add(cons);
					}
				}
				assertXMLTerm(validConses.size() > 0);
				List<Term> contents = new ArrayList<Term>();
				for (Element elem : list) {
					contents.add(parseXML(elem));
				}
				for (String cons : validConses) {
					possibleTerms.add(new TermCons(sort, cons, contents));
				}
				if (possibleTerms.size() == 1) {
					return possibleTerms.get(0);
				} else {
					return new Ambiguity(sort, possibleTerms);
				}
			}
		} catch (Exception e) {
			return new BackendTerm(sort, flattenXML(xml));
		}
	}

	public static String flattenXML(Element xml) {
		List<Element> children = XmlUtil.getChildElements(xml);
		if (children.size() == 0) {
			return xml.getAttribute("op");
		} else {
			String result = xml.getAttribute("op");
			String conn = "(";
			for (Element child : children) {
				result += conn;
				conn = ",";
				result += flattenXML(child);
			}
			result += ")";
			return result;
		}
	}

	private static String getSearchType(SearchType s) {
		if (s == SearchType.ONE) return "1";
		if (s == SearchType.PLUS) return "+";
		if (s == SearchType.STAR) return "*";
		if (s == SearchType.FINAL) return "!";
		return null;
	}

	public KRunResult<SearchResults> search(Integer bound, Integer depth, SearchType searchType, Rule pattern, Term cfg, Set<String> varNames) throws Exception {
		String cmd = "set show command off ." + K.lineSeparator + setCounter() + "search ";
		if (bound != null && depth != null) {
			cmd += "[" + bound + "," + depth + "] ";
		} else if (bound != null) {
			cmd += "[" + bound + "] ";
		} else if (depth != null) {
			cmd += "[," + depth + "] ";
		}
		MaudeFilter maudeFilter = new MaudeFilter();
		cfg.accept(maudeFilter);
		cmd += maudeFilter.getResult() + " ";
		MaudeFilter patternBody = new MaudeFilter();
		pattern.getBody().accept(patternBody);
		String patternString = "=>" + getSearchType(searchType) + " " + patternBody.getResult();
		if (pattern.getCondition() != null) {
			MaudeFilter patternCondition = new MaudeFilter();
			pattern.getCondition().accept(patternCondition);
			patternString += " such that " + patternCondition.getResult() + " = # true(.KList)";
		}
		cmd += patternString + " .";
		cmd += K.lineSeparator + "show search graph .";
		if (K.trace) {
			cmd = "set trace on ." + K.lineSeparator + cmd;
		}
		cmd += getCounter();
		executeKRun(cmd, K.io);
		SearchResults results = new SearchResults(parseSearchResults(pattern), parseSearchGraph(), patternString.trim().matches("=>[!*1+] <_>_</_>\\(generatedTop, B:Bag, generatedTop\\)"), varNames);
		K.stateCounter += results.getGraph().getVertexCount();
		KRunResult<SearchResults> result = new KRunResult<SearchResults>(results);
		result.setRawOutput(FileUtil.getFileContent(K.maude_out));
		return result;
	}

	private DirectedGraph<KRunState, Transition> parseSearchGraph() throws Exception {
		File input = new File(K.maude_output);
		Document doc = XmlUtil.readXML(input);
		NodeList list = null;
		Node nod = null;
		list = doc.getElementsByTagName("graphml");
		assertXML(list.getLength() == 1);
		nod = list.item(0);
		assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
		String text = XmlUtil.convertNodeToString(nod);
		text = text.replaceAll("<data key=\"((rule)|(term))\">", "<data key=\"$1\"><![CDATA[");
		text = text.replaceAll("</data>", "]]></data>");
		StringReader reader = new StringReader(text);
		Transformer<GraphMetadata, DirectedGraph<KRunState, Transition>> graphTransformer = new Transformer<GraphMetadata, DirectedGraph<KRunState, Transition>>() { 
			public DirectedGraph<KRunState, Transition> transform(GraphMetadata g) { 
				return new DirectedSparseGraph<KRunState, Transition>();
			}
		};
		Transformer<NodeMetadata, KRunState> nodeTransformer = new Transformer<NodeMetadata, KRunState>() {
			public KRunState transform(NodeMetadata n) {
				String nodeXmlString = n.getProperty("term");
				Element xmlTerm = XmlUtil.readXML(nodeXmlString).getDocumentElement();
				KRunState ret = parseElement(xmlTerm);
				String id = n.getId();
				id = id.substring(1);
				ret.setStateId(Integer.parseInt(id) + K.stateCounter);
				return ret;
			}
		};
		Transformer<EdgeMetadata, Transition> edgeTransformer = new Transformer<EdgeMetadata, Transition>() {
			public Transition transform(EdgeMetadata e) {
				String edgeXmlString = e.getProperty("rule");
				Element elem = XmlUtil.readXML(edgeXmlString).getDocumentElement();
				String metadataAttribute = elem.getAttribute("metadata");
				Pattern pattern = Pattern.compile("([a-z]*)=\\((.*?)\\)");
				Matcher matcher = pattern.matcher(metadataAttribute);
				String location = null;
				String filename = null;
				while (matcher.find()) {
					String name = matcher.group(1);
					if (name.equals("location"))
						location = matcher.group(2);
					if (name.equals("filename"))
						filename = matcher.group(2);
				}
				if (location == null || location.equals("generated") || filename == null) {
					// we should avoid this wherever possible, but as a quick fix for the
					// superheating problem and to avoid blowing things up by accident when
					// location information is missing, I am creating non-RULE edges.
					String labelAttribute = elem.getAttribute("label");
					if (labelAttribute == null) {
						return new Transition(TransitionType.UNLABELLED);
					} else {
						return new Transition(labelAttribute);
					}
				}
				return new Transition(DefinitionHelper.locations.get(filename + ":(" + location + ")"));
			}
		};

		Transformer<HyperEdgeMetadata, Transition> hyperEdgeTransformer = new Transformer<HyperEdgeMetadata, Transition>() {
			public Transition transform(HyperEdgeMetadata h) {
				throw new RuntimeException("Found a hyper-edge. Has someone been tampering with our intermediate files?");
			}
		};
				
		GraphMLReader2<DirectedGraph<KRunState, Transition>, KRunState, Transition> graphmlParser = new GraphMLReader2<DirectedGraph<KRunState, Transition>, KRunState, Transition>(reader, graphTransformer, nodeTransformer, edgeTransformer, hyperEdgeTransformer);
		return graphmlParser.readGraph();
	}

	private List<SearchResult> parseSearchResults(Rule pattern) throws Exception {
		List<SearchResult> results = new ArrayList<SearchResult>();
		File input = new File(K.maude_output);
		Document doc = XmlUtil.readXML(input);
		NodeList list = null;
		Node nod = null;
		list = doc.getElementsByTagName("search-result");
		for (int i = 0; i < list.getLength(); i++) {
			nod = list.item(i);
			assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
			Element elem = (Element) nod;
			if (elem.getAttribute("solution-number").equals("NONE")) {
				continue;
			}
			int stateNum = Integer.parseInt(elem.getAttribute("state-number"));
			Map<String, Term> rawSubstitution = new HashMap<String, Term>();
			NodeList assignments = elem.getElementsByTagName("assignment");
			for (int j = 0; j < assignments.getLength(); j++) {
				nod = assignments.item(j);
				assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
				elem = (Element) nod;
				List<Element> child = XmlUtil.getChildElements(elem);
				assertXML(child.size() == 2);
				Term result = parseXML(child.get(1));
				rawSubstitution.put(child.get(0).getAttribute("op"), result);
			}

			Term rawResult = (Term)pattern.getBody().accept(new SubstitutionFilter(rawSubstitution));
			KRunState state = new KRunState(rawResult);
			state.setStateId(stateNum + K.stateCounter);
			SearchResult result = new SearchResult(state, rawSubstitution);
			results.add(result);
		}
		list = doc.getElementsByTagName("result");
		nod = list.item(1);
		parseCounter(nod);
		return results;		
	}

	public KRunResult<DirectedGraph<KRunState, Transition>> modelCheck(Term formula, Term cfg) throws Exception {
		MaudeFilter formulaFilter = new MaudeFilter();
		formula.accept(formulaFilter);
		MaudeFilter cfgFilter = new MaudeFilter();
		cfg.accept(cfgFilter);

		String cmd = "mod MCK is" + K.lineSeparator + " including " + K.main_module + " ." + K.lineSeparator + K.lineSeparator + " op #initConfig : -> Bag ." + K.lineSeparator + K.lineSeparator + " eq #initConfig  =" + K.lineSeparator + cfgFilter.getResult() + " ." + K.lineSeparator + "endm" + K.lineSeparator + K.lineSeparator + "red" + K.lineSeparator + "_`(_`)(('modelCheck`(_`,_`)).KLabel,_`,`,_(_`(_`)(Bag2KLabel(#initConfig),.KList)," + K.lineSeparator + formulaFilter.getResult() + ")" + K.lineSeparator + ") .";
		executeKRun(cmd, false);
		KRunResult<DirectedGraph<KRunState, Transition>> result = parseModelCheckResult();
		result.setRawOutput(FileUtil.getFileContent(K.maude_out));
		return result;
	}

	private KRunResult<DirectedGraph<KRunState, Transition>> parseModelCheckResult() throws Exception {
		File input = new File(K.maude_output);
		Document doc = XmlUtil.readXML(input);
		NodeList list = null;
		Node nod = null;
		list = doc.getElementsByTagName("result");
		assertXML(list.getLength() == 1);
		nod = list.item(0);
		assertXML(nod != null && nod.getNodeType() == Node.ELEMENT_NODE);
		Element elem = (Element) nod;
		List<Element> child = XmlUtil.getChildElements(elem);
		assertXML(child.size() == 1);
		String sort = child.get(0).getAttribute("sort");
		String op = child.get(0).getAttribute("op");
		assertXML(op.equals("_`(_`)") && sort.equals("KItem"));
		child = XmlUtil.getChildElements(child.get(0));
		assertXML(child.size() == 2);
		sort = child.get(0).getAttribute("sort");
		op = child.get(0).getAttribute("op");
		assertXML(op.equals("#_") && sort.equals("KLabel"));
		sort = child.get(1).getAttribute("sort");
		op = child.get(1).getAttribute("op");
		assertXML(op.equals(".KList") && sort.equals("KList"));
		child = XmlUtil.getChildElements(child.get(0));
		assertXML(child.size() == 1);
		elem = child.get(0);
		if (elem.getAttribute("op").equals("true") && elem.getAttribute("sort").equals("#Bool")) {
			Term trueTerm = new Constant("Bool", "true");
			return new KRunResult<DirectedGraph<KRunState, Transition>>(null);
		} else {
			sort = elem.getAttribute("sort");
			op = elem.getAttribute("op");
			assertXML(op.equals("LTLcounterexample") && sort.equals("#ModelCheckResult"));
			child = XmlUtil.getChildElements(elem);
			assertXML(child.size() == 2);
			List<MaudeTransition> initialPath = new ArrayList<MaudeTransition>();
			List<MaudeTransition> loop = new ArrayList<MaudeTransition>();
			parseCounterexample(child.get(0), initialPath);
			parseCounterexample(child.get(1), loop);
			DirectedGraph<KRunState, Transition> graph = new DirectedOrderedSparseMultigraph<KRunState, Transition>();
			Transition edge = null;
			KRunState vertex = null;
			for (MaudeTransition trans : initialPath) {
				graph.addVertex(trans.state);
				if (edge != null) {
					graph.addEdge(edge, vertex, trans.state);
				}
				edge = trans.label;
				vertex = trans.state;
			}
			for (MaudeTransition trans : loop) {
				graph.addVertex(trans.state);
				graph.addEdge(edge, vertex, trans.state);
				edge = trans.label;
				vertex = trans.state;
			}
			graph.addEdge(edge, vertex, loop.get(0).state);
			
			return new KRunResult<DirectedGraph<KRunState, Transition>>(graph);
		}
	}

	private static class MaudeTransition {
		public KRunState state;
		public Transition label;

		public MaudeTransition(KRunState state, Transition label) {
			this.state = state;
			this.label = label;
		}
	}

	private static void parseCounterexample(Element elem, List<MaudeTransition> list) throws Exception {
		String sort = elem.getAttribute("sort");
		String op = elem.getAttribute("op");
		List<Element> child = XmlUtil.getChildElements(elem);
		if (sort.equals("#TransitionList") && op.equals("_LTL_")) {
			assertXML(child.size() >= 2);
			for (Element e : child) {
				parseCounterexample(e, list);
			}
		} else if (sort.equals("#Transition") && op.equals("LTL`{_`,_`}")) {
			assertXML(child.size() == 2);
			Term t = parseXML(child.get(0));
		
			List<Element> child2 = XmlUtil.getChildElements(child.get(1));
			sort = child.get(1).getAttribute("sort");
			op = child.get(1).getAttribute("op");
			assertXML(child2.size() == 0 && (sort.equals("#Qid") || sort.equals("#RuleName")));
			String label = op;
			Transition trans;
			if (sort.equals("#RuleName") && op.equals("UnlabeledLtl")) {
				trans = new Transition(TransitionType.UNLABELLED);
			} else {
				trans = new Transition(label);
			}
			list.add(new MaudeTransition(new KRunState(t), trans));
		} else if (sort.equals("#TransitionList") && op.equals("LTLnil")) {
			assertXML(child.size() == 0);
		} else {
			assertXML(false);
		}
	}

	public KRunDebugger debug(Term cfg) throws Exception {
		return new KRunApiDebugger(this, cfg);
	}

	public KRunDebugger debug(SearchResults searchResults) {
		return new KRunApiDebugger(this, searchResults.getGraph());
	}
}