package perf_statistic.server.model;

import com.intellij.util.containers.SortedList;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.STestRun;
import jetbrains.buildServer.serverSide.statistics.ValueProvider;
import jetbrains.buildServer.serverSide.statistics.ValueProviderRegistry;
import jetbrains.buildServer.serverSide.statistics.build.BuildDataStorage;
import org.jetbrains.annotations.NotNull;
import perf_statistic.server.chart_types.PerformanceMetricCompositeVT;
import perf_statistic.server.chart_types.ResponseCodeCompositeVT;
import perf_test_analyzer.common.StringHacks;
import perf_test_analyzer.common.PerformanceMessageParser;
import perf_test_analyzer.common.PerformanceStatisticMetrics;
import perf_test_analyzer.common.PluginConstants;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class PerformanceTestHolder {
	private final LogDataProvider myLogDataProvider;

	private final ValueProviderRegistry myRegistry;
	private final BuildDataStorage myStorage;
	private final SBuildServer myServer;

	private Map<String, PerformanceTestRun> myFailedTestRuns;
	private Map<String, PerformanceTestRun> mySuccessTestRuns;

	private String[] logTitles;

	private long myBuildID = -1;


	public PerformanceTestHolder(@NotNull SBuildServer server, @NotNull final BuildDataStorage storage, @NotNull final ValueProviderRegistry valueProviderRegistry) {
		myStorage = storage;
		myRegistry = valueProviderRegistry;
		myServer = server;
		myLogDataProvider = new LogDataProvider();
	}

	public Collection<PerformanceTestRun> getFailedTestRuns(@NotNull SBuild build) {
		checkOrUpdateTestList(build);
		List<PerformanceTestRun> sortedValues = new SortedList<PerformanceTestRun>(new Comparator<PerformanceTestRun>() {
			@Override
			public int compare(PerformanceTestRun o1, PerformanceTestRun o2) {
				return o1.getTestsGroupName().compareTo((o2.getTestsGroupName()));
			}
		});
		sortedValues.addAll(myFailedTestRuns.values());
		return myFailedTestRuns != null ? sortedValues : Collections.<PerformanceTestRun>emptyList();
	}

	public Collection<PerformanceTestRun> getSuccessTestRuns(@NotNull SBuild build) {
		checkOrUpdateTestList(build);
		List<PerformanceTestRun> sortedValues = new SortedList<PerformanceTestRun>(new Comparator<PerformanceTestRun>() {
			@Override
			public int compare(PerformanceTestRun o1, PerformanceTestRun o2) {
				return o1.getTestsGroupName().compareTo((o2.getTestsGroupName()));
			}
		});
		sortedValues.addAll(mySuccessTestRuns.values());
		return mySuccessTestRuns != null ? sortedValues : Collections.<PerformanceTestRun>emptyList();
	}

	public PerformanceTestRun findTestByName(@NotNull SBuild build, String testName) {
		checkOrUpdateTestList(build);
		return myFailedTestRuns.get(testName) != null ? myFailedTestRuns.get(testName) : mySuccessTestRuns.get(testName);
	}

	public String[] getLogTitles(@NotNull SBuild build) {
		checkOrUpdateTestList(build);
		return logTitles;
	}


	private synchronized void checkOrUpdateTestList(@NotNull SBuild build) {
		if (build.getBuildId() != myBuildID) {
			myFailedTestRuns = new HashMap<String, PerformanceTestRun>();
			mySuccessTestRuns = new HashMap<String, PerformanceTestRun>();

			Map<String, List<BuildProblemData>> problems = new HashMap<String, List<BuildProblemData>>();
			for(BuildProblemData buildProblem : build.getFailureReasons()) {
				if (PluginConstants.BAD_PERFORMANCE_PROBLEM_TYPE.equals(buildProblem.getType())) {
					String fullTestName = buildProblem.getAdditionalData();
					List<BuildProblemData> buildProblemDataList = problems.get(fullTestName);
					if (buildProblemDataList == null) {
						buildProblemDataList = new ArrayList<BuildProblemData>();
					}
					buildProblemDataList.add(buildProblem);
					problems.put(fullTestName, buildProblemDataList);
				}
			}

			for (STestRun test : build.getFullStatistics().getAllTests()) {
				PerformanceTestRun performanceTest = new PerformanceTestRun(test);
				List<BuildProblemData> testProblems = problems.get(performanceTest.getFullName());
				if (testProblems != null) {
					performanceTest.setPerformanceProblems(testProblems);
					myFailedTestRuns.put(performanceTest.getFullName(), performanceTest);
				} else {
					mySuccessTestRuns.put(performanceTest.getFullName(), performanceTest);
				}
				updateOrCreateValueProvider(performanceTest.getChartKey());
				updateOrCreateValueProvider(PerformanceStatisticMetrics.RESPONSE_CODE.getKey() + "_" + performanceTest.getChartKey());
			}
			File log = getPerformanceLogFile(build);
			if (log != null) {
				logTitles = myLogDataProvider.readLog(log);
			}
			myBuildID = build.getBuildId();
		}
	}

	private void updateOrCreateValueProvider(@NotNull String key) {
		ValueProvider valueProvider = myRegistry.getValueProvider(key);
		if (valueProvider == null) {
			if (key.contains("ResponseCode")) {
				valueProvider = new ResponseCodeCompositeVT(myStorage, myRegistry, myServer, key);
			} else {
				valueProvider = new PerformanceMetricCompositeVT(myStorage, myRegistry, myServer, key);
			}
			synchronized (myRegistry) {
				myRegistry.registerorFindValueProvider(valueProvider);
			}
		}
	}

	private File getPerformanceLogFile(@NotNull SBuild build) {
		File[] artifacts = build.getArtifactsDirectory().listFiles();
		if (artifacts != null) {
			for (File artifact : artifacts) {
				String absPath = artifact.getName();

				String resultLogFile = build.getParametersProvider().get(PluginConstants.PARAMS_AGGREGATE_FILE);
				if (resultLogFile != null && absPath.equals(resultLogFile))  {  // todo: fix it with value from feature parameters
					return artifact;
				}
			}
		}
		return null;
	}

	private final class LogDataProvider {
		private final Pattern delimiter = PerformanceMessageParser.DELIMITER_PATTERN;

		/**
		 * Fills result info details to test objects from log file
		 * @param file
		 * @return titles of results log lines
		 */
		public String[] readLog(@NotNull final File file) {
			BufferedReader reader = null;
			String[] titles = null;
			try {
				reader = new BufferedReader(new FileReader(file));
				if (reader.ready()) {
					titles = delimiter.split(reader.readLine().trim());
				}
				while (reader.ready()) {
					String[] items = delimiter.split(reader.readLine().trim());
					if (checkItem(items)) {
						long startTime = Long.parseLong(items[0]);
						long elapsedTime = Long.parseLong(items[1]);
						String label = StringHacks.checkTestName(items[2].trim());
						items[2] = label;
						String responseCode = items[3].trim();

						PerformanceTestRun test = myFailedTestRuns.get(label);
						if (test == null) {
							test = mySuccessTestRuns.get(label);
						}
						if (test != null) {
							test.addResponseCode(responseCode);
							test.addTimeValue(startTime, elapsedTime);
							test.addLogLine(startTime, items);
						}
					}
				}
			} catch (FileNotFoundException e) {
				Loggers.STATS.error(PluginConstants.FEATURE_TYPE_REMOTE_MONITORING + " plugin error. File " + file.getAbsolutePath() + " not found!", e);
			} catch (IOException e) {
				Loggers.STATS.error(PluginConstants.FEATURE_TYPE_REMOTE_MONITORING + " plugin error. Error reading file " + file.getAbsolutePath(), e);
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (IOException e) {
						Loggers.STATS.error(PluginConstants.FEATURE_TYPE_REMOTE_MONITORING + " plugin error. Error closing file " + file.getAbsolutePath(), e);
					}
				}
			}
			return titles;
		}

		private boolean checkItem(String[] values) {
			if (values.length < 3) {
				Loggers.STATS.error(PluginConstants.FEATURE_TYPE_REMOTE_MONITORING + " plugin error. \nItem: timestamp\tresultValue\tlabel \n Found: " + Arrays.toString(values));
				return false;
			}
			return (values[0].matches("\\d+") && values[1].matches("[0-9]*\\.?[0-9]*([Ee][+-]?[0-9]+)?"));
		}
	}
}
