/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah
 *
 * Partially based on exit trapping in Nailgun by Pete Kirkham,
 * copyright 2004, Martian Software, Inc
 * licensed under Apache 2.0 License.
 */
package sbt

import scala.collection.Set

/** This provides functionality to catch System.exit calls to prevent the JVM from terminating.
* This is useful for executing user code that may call System.exit, but actually exiting is
* undesirable.  This file handles the call to exit by disposing all top-level windows and interrupting
* all user started threads.  It does not stop the threads and does not call shutdown hooks.  It is
* therefore inappropriate to use this with code that requires shutdown hooks or creates threads that
* do not terminate.  This category of code should only be called by forking the JVM. */
object TrapExit
{
	/** Executes the given thunk in a context where System.exit(code) throws
	* a custom SecurityException, which is then caught and the exit code returned
	* in Left.  Otherwise, the result of calling execute is returned. No other
	* exceptions are handled by this method.*/
	def apply[T](execute: => T, log: Logger): Either[Int, T] =
	{
		val originalSecurityManager = System.getSecurityManager
		val newSecurityManager = new TrapExitSecurityManager(originalSecurityManager)
		System.setSecurityManager(newSecurityManager)
		
		/** Take a snapshot of the threads that existed before execution in order to determine
		* the threads that were created by 'execute'.*/
		val originalThreads = allThreads
		
		val result =
			try { Right(execute) }
			catch { case t: Throwable => Left(exitCode(t)) }
		val newResult =
			result match
			{
				case Right(r) =>
				{
					/** In this case, the code completed without calling System.exit.  It possibly started other threads
					* and so we replace the uncaught exception handlers on those threads to handle System.exit.  Then we
					* wait for those threads to die.*/
					val code = new ExitCode
					processThreads(originalThreads, thread => replaceHandler(thread, originalThreads, code))
					processThreads(originalThreads, thread => waitOnThread(thread, log))
					code.value match
					{
						case Some(exitCode) => Left(exitCode)
						case None => result
					}
				}
				case Left(code) =>
				{
					/** The other case is that the code directly called System.exit.  In this case, we dispose all
					* top-level windows and interrupt user-started threads.*/
					stopAll(originalThreads)
					result
				}
			}
			
		System.setSecurityManager(originalSecurityManager)

		newResult
	}
	/** Waits for the given thread to exit. */
	private def waitOnThread(thread: Thread, log: Logger)
	{
		log.debug("Waiting for thread " + thread.getName + " to exit")
		thread.join
		log.debug("\tThread " + thread.getName + " exited.")
	}
	/** Replaces the uncaught exception handler with one that handles the SecurityException thrown by System.exit and
	* otherwise delegates to the original handler*/
	private def replaceHandler(thread: Thread, originalThreads: Set[Thread], code: ExitCode)
	{
		thread.setUncaughtExceptionHandler(new ExitHandler(thread.getUncaughtExceptionHandler, originalThreads, code))
	}
	/** Returns the exit code of the System.exit that caused the given Exception, or rethrows the exception
	* if its cause was not calling System.exit.*/
	private def exitCode(e: Throwable): Int =
	{
		e match
		{
			case x: TrapExitSecurityException => x.exitCode
			case _ => 
			{
				val cause = e.getCause
				if(cause == null)
					throw e
				else
					exitCode(cause)
			}
		}
	}
	
	import scala.collection.jcl.Conversions.convertSet
	/** Returns all threads that are not in the 'system' thread group and are not the AWT implementation
	* thread (AWT-XAWT, AWT-Windows, ...)*/
	private def allThreads: Set[Thread] =
	{
		val threads = convertSet(Thread.getAllStackTraces.keySet)
		for(thread <- threads.toList if isSystemThread(thread))
			threads -= thread
		threads
	}
	/** Returns true if the given thread is in the 'system' thread group and is an AWT thread other than
	* AWT-EventQueue or AWT-Shutdown.*/
	private def isSystemThread(t: Thread) =
	{
		val name = t.getName
		if(name.startsWith("AWT-"))
			!(name.startsWith("AWT-EventQueue") || name.startsWith("AWT-Shutdown"))
		else
		{
			val group = t.getThreadGroup
			(group != null) && (group.getName == "system")
		}
	}
	/** Calls the provided function for each thread in the system as provided by the 
	* allThreads function except those in ignoreThreads.*/
	private def processThreads(ignoreThreads: Set[Thread], process: Thread => Unit)
	{
		allThreads.filter(thread => !ignoreThreads.contains(thread)).foreach(process)
	}
	/** Handles System.exit by disposing all frames and calling interrupt on all user threads */
	private def stopAll(originalThreads: Set[Thread])
	{
		val allFrames = java.awt.Frame.getFrames
		if(allFrames.length > 0)
		{
			allFrames.foreach(_.dispose) // dispose all top-level windows, which will cause the AWT-EventQueue-* threads to exit
			Thread.sleep(2000) // AWT Thread doesn't exit immediately, so wait to interrupt it
		}
		// interrupt all threads that appear to have been started by the user
		processThreads(originalThreads, thread => if(!thread.getName.startsWith("AWT-")) thread.interrupt)
	}
	/** An uncaught exception handler that delegates to the original uncaught exception handler except when
	* the cause was a call to System.exit (which generated a SecurityException)*/
	class ExitHandler(originalHandler: Thread.UncaughtExceptionHandler, originalThreads: Set[Thread], codeHolder: ExitCode) extends Thread.UncaughtExceptionHandler
	{
		def uncaughtException(t: Thread, e: Throwable)
		{
			try
			{
				codeHolder.set(exitCode(e)) // will rethrow e if it was not because of a call to System.exit
				stopAll(originalThreads)
			}
			catch
			{
				case _ => originalHandler.uncaughtException(t, e)
			}
		}
	}
}
private class ExitCode extends NotNull
{
	private var code: Option[Int] = None
	def set(c: Int)
	{
		synchronized
		{
			code match
			{
				case Some(existing) => ()
				case None => code = Some(c)
			}
		}
	}
	def value: Option[Int] = synchronized { code }
}
///////  These two classes are based on similar classes in Nailgun
/** A custom SecurityManager to disallow System.exit. */
private class TrapExitSecurityManager(delegateManager: SecurityManager) extends SecurityManager
{
	import java.security.Permission
	override def checkExit(status: Int)
	{
		val stack = Thread.currentThread.getStackTrace
		if(stack == null || stack.exists(isRealExit))
			throw new TrapExitSecurityException(status)
	}
	/** This ensures that only actual calls to exit are trapped and not just calls to check if exit is allowed.*/
	private def isRealExit(element: StackTraceElement): Boolean =
		element.getClassName == "java.lang.Runtime" && element.getMethodName == "exit"
	override def checkPermission(perm: Permission)
	{
		if(delegateManager != null)
			delegateManager.checkPermission(perm)
	}
	override def checkPermission(perm: Permission, context: AnyRef)
	{
		if(delegateManager != null)
			delegateManager.checkPermission(perm, context)
	}
}
/** A custom SecurityException that tries not to be caught.*/
private class TrapExitSecurityException(val exitCode: Int) extends SecurityException
{
	private var accessAllowed = false
	def allowAccess
	{
		accessAllowed = true
	}
	override def printStackTrace = ifAccessAllowed(super.printStackTrace)
	override def toString = ifAccessAllowed(super.toString)
	override def getCause = ifAccessAllowed(super.getCause)
	override def getMessage = ifAccessAllowed(super.getMessage)
	override def fillInStackTrace = ifAccessAllowed(super.fillInStackTrace)
	override def getLocalizedMessage = ifAccessAllowed(super.getLocalizedMessage)
	private def ifAccessAllowed[T](f: => T): T =
	{
		if(accessAllowed)
			f
		else
			throw this
	}
}