package org.adoptopenjdk.jitwatch.parser;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_COMPILER;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_COMPILE_ID;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_COMPILE_KIND;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_FREE_CODE_CACHE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_METHOD;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_NMSIZE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_STAMP;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_STAMP_COMPLETED;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C1;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C2;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C2N;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C_QUOTE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C_SPACE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.DEBUG_LOGGING;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.J9;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.S_DOT;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.S_SLASH;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_CODE_CACHE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_TASK;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_TASK_DONE;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.adoptopenjdk.jitwatch.core.IJITListener;
import org.adoptopenjdk.jitwatch.core.JITWatchConfig;
import org.adoptopenjdk.jitwatch.core.TagProcessor;
import org.adoptopenjdk.jitwatch.model.CodeCacheEvent;
import org.adoptopenjdk.jitwatch.model.CodeCacheEvent.CodeCacheEventType;
import org.adoptopenjdk.jitwatch.model.EventType;
import org.adoptopenjdk.jitwatch.model.IMetaMember;
import org.adoptopenjdk.jitwatch.model.JITDataModel;
import org.adoptopenjdk.jitwatch.model.JITEvent;
import org.adoptopenjdk.jitwatch.model.LogParseException;
import org.adoptopenjdk.jitwatch.model.MetaClass;
import org.adoptopenjdk.jitwatch.model.ParsedClasspath;
import org.adoptopenjdk.jitwatch.model.SplitLog;
import org.adoptopenjdk.jitwatch.model.Tag;
import org.adoptopenjdk.jitwatch.model.Task;
import org.adoptopenjdk.jitwatch.model.assembly.AssemblyProcessor;
import org.adoptopenjdk.jitwatch.util.ClassUtil;
import org.adoptopenjdk.jitwatch.util.ParseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractLogParser implements ILogParser
{
	protected static final Logger logger = LoggerFactory.getLogger(AbstractLogParser.class);

	protected JITDataModel model;

	protected String vmCommand = null;

	protected boolean reading = false;

	protected boolean hasTraceClassLoad = false;

	protected boolean hasParseError = false;
	protected String errorDialogTitle;
	protected String errorDialogBody;

	protected IMetaMember currentMember = null;

	protected IJITListener jitListener = null;
	protected ILogParseErrorListener errorListener = null;

	protected boolean inHeader = false;

	protected long parseLineNumber;
	protected long processLineNumber;

	protected JITWatchConfig config = new JITWatchConfig();

	protected TagProcessor tagProcessor;

	protected AssemblyProcessor asmProcessor;

	protected SplitLog splitLog = new SplitLog();

	public AbstractLogParser(IJITListener jitListener)
	{
		model = new JITDataModel();

		this.jitListener = jitListener;
	}

	@Override
	public void setConfig(JITWatchConfig config)
	{
		this.config = config;
	}

	@Override
	public JITWatchConfig getConfig()
	{
		return config;
	}

	@Override
	public SplitLog getSplitLog()
	{
		return splitLog;
	}

	@Override
	public ParsedClasspath getParsedClasspath()
	{
		return config.getParsedClasspath();
	}

	protected void configureDisposableClassLoader()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("configureDisposableClassLoader()");
		}

		List<String> configuredClassLocations = config.getConfiguredClassLocations();
		List<String> parsedClassLocations = getParsedClasspath().getClassLocations();

		int configuredClasspathCount = configuredClassLocations.size();
		int parsedClasspathCount = parsedClassLocations.size();

		List<URL> classpathURLList = new ArrayList<URL>(configuredClasspathCount + parsedClasspathCount);

		for (String filename : configuredClassLocations)
		{
			URI uri = new File(filename).toURI();

			jitListener.handleLogEntry("Adding configured classpath: " + uri.toString());

			if (DEBUG_LOGGING)
			{
				logger.debug("adding to classpath {}", uri.toString());
			}			
			
			try
			{
				classpathURLList.add(uri.toURL());
			}
			catch (MalformedURLException e)
			{
				logger.error("Could not create URL: {} ", uri, e);
			}
		}

		for (String filename : getParsedClasspath().getClassLocations())
		{
			if (!configuredClassLocations.contains(filename))
			{
				URI uri = new File(filename).toURI();

				jitListener.handleLogEntry("Adding parsed classpath: " + uri.toString());

				try
				{
					classpathURLList.add(uri.toURL());
				}
				catch (MalformedURLException e)
				{
					logger.error("Could not create URL: {} ", uri, e);
				}
			}
		}

		ClassUtil.initialise(classpathURLList);
	}

	protected void logEvent(JITEvent event)
	{
		if (jitListener != null)
		{
			jitListener.handleJITEvent(event);
		}
	}

	protected void logError(String entry)
	{
		if (jitListener != null)
		{
			jitListener.handleErrorEntry(entry);
		}
	}

	@Override
	public JITDataModel getModel()
	{
		return model;
	}

	@Override
	public void reset()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("Log parser reset()");
		}

		ClassUtil.clear();

		getModel().reset();

		splitLog.clear();

		hasTraceClassLoad = false;

		hasParseError = false;
		errorDialogTitle = null;
		errorDialogBody = null;

		reading = false;

		inHeader = false;

		currentMember = null;

		vmCommand = null;

		parseLineNumber = 0;
		processLineNumber = 0;

		tagProcessor = new TagProcessor();

		asmProcessor = new AssemblyProcessor();
	}

	@Override
	public void stopParsing()
	{
		reading = false;
	}

	public IMetaMember findMemberWithSignature(String logSignature)
	{
		IMetaMember result = null;

		try
		{
			result = ParseUtil.findMemberWithSignature(model, logSignature);
		}
		catch (LogParseException ex)
		{
			if (DEBUG_LOGGING)
			{
				logger.debug("Could not parse signature: {}", logSignature);
				logger.debug("Exception was {}", ex.getMessage());
			}

			logError("Could not parse line " + processLineNumber + " : " + logSignature + " : " + ex.getMessage());
		}

		return result;
	}

	@Override
	public boolean hasParseError()
	{
		return hasParseError;
	}

	@Override
	public String getVMCommand()
	{
		return vmCommand;
	}

	@Override
	public void discardParsedLogs()
	{
		splitLog.clear();
		splitLog = new SplitLog();
	}

	protected void addToClassModel(String fqClassName)
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("addToClassModel {}", fqClassName);
		}
		
		Class<?> clazz = null;

		MetaClass metaClass = model.getPackageManager().getMetaClass(fqClassName);

		if (metaClass != null)
		{
			return;
		}

		try
		{
			clazz = ClassUtil.loadClassWithoutInitialising(fqClassName);

			if (clazz != null)
			{
				model.buildAndGetMetaClass(clazz);
			}
		}
		catch (ClassNotFoundException cnf)
		{
			if (!ParseUtil.possibleLambdaMethod(fqClassName))
			{
				logError("ClassNotFoundException: '" + fqClassName + C_QUOTE);
			}
		}
		catch (NoClassDefFoundError ncdf)
		{
			logError("NoClassDefFoundError: '" + fqClassName + C_SPACE + "requires " + ncdf.getMessage() + C_QUOTE);
		}
		catch (UnsupportedClassVersionError ucve)
		{
			hasParseError = true;
			errorDialogTitle = "UnsupportedClassVersionError for class " + fqClassName;
			errorDialogBody = "Could not load " + fqClassName + " as the class file version is too recent for this JVM.";

			logError(
					"UnsupportedClassVersionError! Tried to load a class file with an unsupported format (later version than this JVM)");
			logger.error("Class file for {} created in a later JVM version", fqClassName, ucve);
		}
		catch (Throwable t)
		{
			// Possibly a VerifyError
			logger.error("Could not addClassToModel {}", fqClassName, t);
			logError("Exception: '" + fqClassName + C_QUOTE);
		}
	}

	private void logSplitStats()
	{
		logger.debug("Header lines        : {}", splitLog.getHeaderLines().size());
		logger.debug("ClassLoader lines   : {}", splitLog.getClassLoaderLines().size());
		logger.debug("LogCompilation lines: {}", splitLog.getCompilationLines().size());
		logger.debug("Assembly lines      : {}", splitLog.getAssemblyLines().size());
	}

	@Override
	public void processLogFile(File logFile, ILogParseErrorListener errorListener)
	{
		reset();

		configureDisposableClassLoader();
		
		// tell listener to reset any data
		jitListener.handleReadStart();

		this.errorListener = errorListener;

		splitLogFile(logFile);

		if (DEBUG_LOGGING)
		{
			logSplitStats();
		}

		parseLogFile();

		jitListener.handleReadComplete();
	}

	protected void handleTagQueued(Tag tag)
	{
		handleMethodLine(tag, EventType.QUEUE);
	}

	protected void handleTagNMethod(Tag tag)
	{
		Map<String, String> tagAttributes = tag.getAttributes();

		String attrCompiler = tagAttributes.get(ATTR_COMPILER);

		renameCompilationCompletedTimestamp(tag);

		if (attrCompiler != null)
		{
			if (C1.equals(attrCompiler))
			{
				handleMethodLine(tag, EventType.NMETHOD_C1);
			}
			else if (C2.equals(attrCompiler))
			{
				handleMethodLine(tag, EventType.NMETHOD_C2);
			}
			else if (J9.equals(attrCompiler))
			{
				handleMethodLine(tag, EventType.NMETHOD_J9);
			}
			else
			{
				logError("Unexpected Compiler attribute: " + attrCompiler);
			}
		}
		else
		{
			String attrCompileKind = tagAttributes.get(ATTR_COMPILE_KIND);

			if (attrCompileKind != null && C2N.equals(attrCompileKind))
			{
				handleMethodLine(tag, EventType.NMETHOD_C2N);
			}
			else
			{
				logError("Missing Compiler attribute " + tag);
			}
		}
	}

	protected void handleTagTask(Task task)
	{
		handleMethodLine(task, EventType.TASK);

		Tag tagCodeCache = task.getFirstNamedChild(TAG_CODE_CACHE);
		Tag tagTaskDone = task.getFirstNamedChild(TAG_TASK_DONE);

		if (tagTaskDone != null)
		{
			handleTaskDone(tagTaskDone);

			if (tagCodeCache != null)
			{
				long stamp = ParseUtil.parseStampFromTag(tagTaskDone);
				long freeCodeCache = ParseUtil.parseLongAttributeFromTag(tagCodeCache, ATTR_FREE_CODE_CACHE);
				long nativeCodeSize = ParseUtil.parseLongAttributeFromTag(tagTaskDone, ATTR_NMSIZE);

				storeCodeCacheEventDetail(CodeCacheEventType.COMPILATION, stamp, nativeCodeSize, freeCodeCache);
			}
		}
		else
		{
			logger.error("{} not found in {}", TAG_TASK_DONE, task);
		}
	}

	protected void storeCodeCacheEvent(CodeCacheEventType eventType, Tag tag)
	{
		storeCodeCacheEventDetail(eventType, ParseUtil.parseStampFromTag(tag), 0, 0);
	}

	private void storeCodeCacheEventDetail(CodeCacheEventType eventType, long stamp, long nativeCodeSize, long freeCodeCache)
	{
		CodeCacheEvent codeCacheEvent = new CodeCacheEvent(eventType, stamp, nativeCodeSize, freeCodeCache);

		model.addCodeCacheEvent(codeCacheEvent);
	}

	private void handleMethodLine(Tag tag, EventType eventType)
	{
		Map<String, String> attrs = tag.getAttributes();

		String attrMethod = attrs.get(ATTR_METHOD);

		if (attrMethod != null)
		{
			attrMethod = attrMethod.replace(S_SLASH, S_DOT);

			handleMember(attrMethod, attrs, eventType, tag);
		}
	}

	private void handleMember(String signature, Map<String, String> attrs, EventType type, Tag tag)
	{
		IMetaMember metaMember = findMemberWithSignature(signature);

		long stampTime = ParseUtil.getStamp(attrs);

		if (metaMember != null)
		{
			switch (type)
			{
			case QUEUE:
			{
				metaMember.setTagTaskQueued(tag);
				JITEvent queuedEvent = new JITEvent(stampTime, type, metaMember);
				model.addEvent(queuedEvent);
				logEvent(queuedEvent);
			}
				break;
			case NMETHOD_C1:
			case NMETHOD_C2:
			case NMETHOD_C2N:
			case NMETHOD_J9:
			{
				metaMember.setTagNMethod(tag);
				metaMember.getMetaClass().incCompiledMethodCount();
				model.updateStats(metaMember, attrs);

				JITEvent compiledEvent = new JITEvent(stampTime, type, metaMember);
				model.addEvent(compiledEvent);
				logEvent(compiledEvent);
			}
				break;
			case TASK:
			{
				metaMember.setTagTask((Task) tag);
				currentMember = metaMember;
			}
				break;
			default:
				break;
			}
		}
	}

	private void handleTaskDone(Tag tagTaskDone)
	{
		Map<String, String> attrs = tagTaskDone.getAttributes();

		if (attrs.containsKey(ATTR_NMSIZE))
		{
			long nmsize = Long.parseLong(attrs.get(ATTR_NMSIZE));
			model.addNativeBytes(nmsize);
		}

		if (currentMember != null)
		{
			Tag parent = tagTaskDone.getParent();

			String compileID = null;

			if (TAG_TASK.equals(parent.getName()))
			{
				compileID = parent.getAttributes().get(ATTR_COMPILE_ID);

				if (compileID != null)
				{
					currentMember.setTagTaskDone(compileID, tagTaskDone);
				}
				else
				{
					logger.warn("No compile_id attribute found on task");
				}
			}
			else
			{
				logger.warn("Unexpected parent of task_done: {}", parent.getName());
			}

			// prevents attr overwrite by next task_done if next member not
			// found due to classpath issues
			currentMember = null;
		}
	}

	private void renameCompilationCompletedTimestamp(Tag tag)
	{
		String compilationCompletedStamp = tag.getAttributes().get(ATTR_STAMP);

		if (compilationCompletedStamp != null)
		{
			tag.getAttributes().remove(ATTR_STAMP);
			tag.getAttributes().put(ATTR_STAMP_COMPLETED, compilationCompletedStamp);
		}
	}

	protected abstract void parseLogFile();

	protected abstract void splitLogFile(File logFile);

	protected abstract void handleTag(Tag tag);
}