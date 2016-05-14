package org.antlr.codebuff.validation;

import org.antlr.codebuff.Formatter;
import org.antlr.v4.runtime.misc.Triple;

import java.util.List;

import static org.antlr.codebuff.Tool.JAVA_DESCR;

public class FormatJava {
	public static void main(String[] args) throws Exception {
		LeaveOneOutValidator validator = new LeaveOneOutValidator("corpus/java/training/antlr4-tool", JAVA_DESCR);
		Triple<List<Formatter>,List<Float>,List<Float>> results = validator.validateDocuments(false, "output");
		System.out.println(results.b);
		System.out.println(results.c);
	}
}