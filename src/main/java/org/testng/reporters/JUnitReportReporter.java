package org.testng.reporters;

import org.testng.IReporter;
import org.testng.ISuite;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.collections.ListMultiMap;
import org.testng.collections.Lists;
import org.testng.collections.Maps;
import org.testng.collections.Sets;
import org.testng.internal.Utils;
import org.testng.xml.XmlSuite;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class JUnitReportReporter implements IReporter {

  @Override
  public void generateReport(List<XmlSuite> xmlSuites, List<ISuite> suites,
      String defaultOutputDirectory) {

    Map<Class<?>, Set<ITestResult>> results = Maps.newHashMap();
    ListMultiMap<Object, ITestResult> befores = Maps.newListMultiMap();
    ListMultiMap<Object, ITestResult> afters = Maps.newListMultiMap();
    for (ISuite suite : suites) {
      Map<String, ISuiteResult> suiteResults = suite.getResults();
      for (ISuiteResult sr : suiteResults.values()) {
        ITestContext tc = sr.getTestContext();
        addResults(tc.getPassedTests().getAllResults(), results);
        addResults(tc.getFailedTests().getAllResults(), results);
        addResults(tc.getSkippedTests().getAllResults(), results);
        addResults(tc.getFailedConfigurations().getAllResults(), results);
        for (ITestResult tr : tc.getPassedConfigurations().getAllResults()) {
          if (tr.getMethod().isBeforeMethodConfiguration()) {
            befores.put(tr.getInstance(), tr);
          }
          if (tr.getMethod().isAfterMethodConfiguration()) {
            afters.put(tr.getInstance(), tr);
          }
        }
      }
    }

    // A list of iterators for all the passed configuration, explanation below
//    ListMultiMap<Class<?>, ITestResult> beforeConfigurations = Maps.newListMultiMap();
//    ListMultiMap<Class<?>, ITestResult> afterConfigurations = Maps.newListMultiMap();
//    for (Map.Entry<Class<?>, Set<ITestResult>> es : passedConfigurations.entrySet()) {
//      for (ITestResult tr : es.getValue()) {
//        ITestNGMethod method = tr.getMethod();
//        if (method.isBeforeMethodConfiguration()) {
//          beforeConfigurations.put(method.getRealClass(), tr);
//        }
//        if (method.isAfterMethodConfiguration()) {
//          afterConfigurations.put(method.getRealClass(), tr);
//        }
//      }
//    }
//    Map<Object, Iterator<ITestResult>> befores = Maps.newHashMap();
//    for (Map.Entry<Class<?>, List<ITestResult>> es : beforeConfigurations.getEntrySet()) {
//      List<ITestResult> tr = es.getValue();
//      for (ITestResult itr : es.getValue()) {
//      }
//    }
//    Map<Class<?>, Iterator<ITestResult>> afters = Maps.newHashMap();
//    for (Map.Entry<Class<?>, List<ITestResult>> es : afterConfigurations.getEntrySet()) {
//      afters.put(es.getKey(), es.getValue().iterator());
//    }

    for (Map.Entry<Class<?>, Set<ITestResult>> entry : results.entrySet()) {
      Class<?> cls = entry.getKey();
      Properties p1 = new Properties();
      p1.setProperty("name", cls.getName());
      Date timeStamp = Calendar.getInstance().getTime();
      p1.setProperty(XMLConstants.ATTR_TIMESTAMP, timeStamp.toGMTString());

      List<TestTag> testCases = Lists.newArrayList();
      int failures = 0;
      int errors = 0;
      int skipped= 0;
      int testCount = 0;
      float totalTime = 0;

      for (ITestResult tr: entry.getValue()) {
        TestTag testTag = new TestTag();

        Properties p2 = new Properties();
        p2.setProperty("classname", cls.getName());
        p2.setProperty("name", getTestName(tr));
        long time = tr.getEndMillis() - tr.getStartMillis();

        time += getNextConfiguration(befores, tr);
        time += getNextConfiguration(afters, tr);

        p2.setProperty("time", "" + formatTime(time));
        Throwable t = tr.getThrowable();
        switch (tr.getStatus()) {
          case ITestResult.SUCCESS:
            break;

          case ITestResult.SKIP:
          case ITestResult.SUCCESS_PERCENTAGE_FAILURE:
            skipped++;
            testTag.childTag = "skipped";
            break;

          case ITestResult.FAILURE:
            if (t instanceof AssertionError) {
              failures++;
              testTag.childTag = "failure";
            } else {
              errors++;
              testTag.childTag = "error";
            }
            if (t != null) {
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw);
          t.printStackTrace(pw);
          testTag.message = t.getMessage();
          testTag.type = t.getClass().getName();
          testTag.stackTrace = sw.toString();
        }
            break;
        }

        totalTime += time;
        testCount++;
        testTag.properties = p2;
        testCases.add(testTag);
      }

      p1.setProperty("failures", "" + failures);
      p1.setProperty("errors", "" + errors);
      p1.setProperty("skipped", "" + skipped);
      p1.setProperty("name", cls.getName());
      p1.setProperty("tests", "" + testCount);
      p1.setProperty("time", "" + formatTime(totalTime));
      try {
        p1.setProperty(XMLConstants.ATTR_HOSTNAME, InetAddress.getLocalHost().getHostName());
      } catch (UnknownHostException e) {
        // ignore
      }

      //
      // Now that we have all the information we need, generate the file
      //
      XMLStringBuffer xsb = new XMLStringBuffer();
      xsb.addComment("Generated by " + getClass().getName());

      xsb.push("testsuite", p1);
      for (TestTag testTag : testCases) {
        if (putElement(xsb, "testcase", testTag.properties, testTag.childTag != null)) {
          Properties p = new Properties();
          safeSetProperty(p, "message", testTag.message);
          safeSetProperty(p, "type", testTag.type);

          if (putElement(xsb, testTag.childTag, p, testTag.stackTrace != null)) {
            xsb.addCDATA(testTag.stackTrace);
            xsb.pop(testTag.childTag);
          }
          xsb.pop("testcase");
        }
      }
      xsb.pop("testsuite");

      String outputDirectory = defaultOutputDirectory + File.separator + "junitreports";
      Utils.writeUtf8File(outputDirectory, getFileName(cls), xsb.toXML());
    }

//    System.out.println(xsb.toXML());
//    System.out.println("");

  }

  /** Put a XML start or empty tag to the XMLStringBuffer depending on hasChildElements parameter */
  private boolean putElement(XMLStringBuffer xsb, String tagName, Properties attributes, boolean hasChildElements) {
    if (hasChildElements) {
      xsb.push(tagName, attributes);
    }
    else {
      xsb.addEmptyElement(tagName, attributes);
    }
    return hasChildElements;
  }

  /** Set property if value is non-null */
  private void safeSetProperty(Properties p, String key, String value) {
    if (value != null) {
      p.setProperty(key, value);
    }
  }

  /**
   * Add the time of the configuration method to this test method.
   *
   * The only problem with this method is that the timing of a test method
   * might not be added to the time of the same configuration method that ran before
   * it but since they should all be equivalent, this should never be an issue.
   */
  private long getNextConfiguration(ListMultiMap<Object, ITestResult> configurations,
      ITestResult tr)
  {
    long result = 0;

    List<ITestResult> confResults = configurations.get(tr.getInstance());
    Map<ITestNGMethod, ITestResult> seen = Maps.newHashMap();
    if (confResults != null) {
      for (ITestResult r : confResults) {
        if (! seen.containsKey(r.getMethod())) {
          result += r.getEndMillis() - r.getStartMillis();
          seen.put(r.getMethod(), r);
        }
      }
      confResults.removeAll(seen.values());
    }

    return result;
  }

  protected String getFileName(Class cls) {
    return "TEST-" + cls.getName() + ".xml";
  }

  protected String getTestName(ITestResult tr) {
    return tr.getMethod().getMethodName();
  }

  private String formatTime(float time) {
    DecimalFormatSymbols symbols = new DecimalFormatSymbols();
    // JUnitReports wants points here, regardless of the locale
    symbols.setDecimalSeparator('.');
    DecimalFormat format = new DecimalFormat("#.###", symbols);
    format.setMinimumFractionDigits(3);
    return format.format(time / 1000.0f);
  }

  static class TestTag {
    public Properties properties;
    public String message;
    public String type;
    public String stackTrace;
    public String childTag;
  }

  private void addResults(Set<ITestResult> allResults, Map<Class<?>, Set<ITestResult>> out) {
    for (ITestResult tr : allResults) {
      Class<?> cls = tr.getMethod().getTestClass().getRealClass();
      Set<ITestResult> l = out.get(cls);
      if (l == null) {
        l = Sets.newHashSet();
        out.put(cls, l);
      }
      l.add(tr);
    }
  }

}
