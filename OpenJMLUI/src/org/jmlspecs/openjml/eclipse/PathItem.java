/* This file is part of the OpenJML plugin
 * Copyright (c) 2006-2013 David R. Cok
 * @author David R. Cok
 */
package org.jmlspecs.openjml.eclipse;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;

// FIXME - would like to have paths with variables, e.g. ${user.dir}
// FIXME - Source paths do not include items in referenced projects
// FIXME - Source paths ignore the included/excluded declarations in the Java Build Path

/** These classes manage components of the source and specs paths, much as Eclipse
 * has different kinds of elements of the classpath. The paths have a serialized
 * text version that is stored in a persistent property of the Java project.
 */
abstract public class PathItem {
	
	/** The string that separates the representation of the kind of PathItem
	 * from the value.
	 */
	public final static String sep = "#"; // Presumed to be a single character //$NON-NLS-1$

	/** The separator that separates elements of the paths in the serialized
	 * version; this character may not appear in a file name.
	 */
	public final static String split = ","; //$NON-NLS-1$

	/** The key used to store the sourcepath as a persistent property. */
	public final static QualifiedName sourceKey = new QualifiedName(Activator.PLUGIN_ID,"sourcepath"); //$NON-NLS-1$

	/** The key used to store the specspath as a persistent property. */
	public final static QualifiedName specsKey = new QualifiedName(Activator.PLUGIN_ID,"specspath"); //$NON-NLS-1$

	/** Creates an appropriate PathItem from a raw file path. */
	public static PathItem create(String pureString) {
		IFile f = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(pureString));
		if (f != null) {
			return new WorkspacePath(f.getFullPath().toString());
		}
		return new AbsolutePath(pureString);
	}
	
	public static boolean add(IJavaProject jproject, QualifiedName key, PathItem p) throws CoreException {
		String s = getEncodedPath(jproject,key);
		String sp = p.toEncodedString();
		if (s.isEmpty()) {
			s = sp;
		} else {
			// Check whether the item is already present
			// Return false if it is
			int k = s.indexOf(sp); 
			if (k >= 0) { 
				int kk = s.indexOf(split,k);
				if (kk == -1) kk = s.length();
				int kb = s.lastIndexOf(split,k);
				if ((k == 0 || kb + split.length() == k) &&
				    (kk == k + sp.length())) return false;
			}
			s = s + split + sp;
		}
		jproject.getProject().setPersistentProperty(key,s);
		return true;
	}
	
	public static boolean remove(IJavaProject jproject, QualifiedName key, PathItem p) throws CoreException {
		String s = getEncodedPath(jproject,key);
		String sp = p.toEncodedString();
		int k = s.indexOf(sp);
		if (k == -1) return false;
		if (k != 0 && k != s.lastIndexOf(split,k) + split.length()) return false;
		int kkb = k == 0 ? 0 : k - split.length();
		int kk = s.indexOf(split,k);
		if (kk == -1) kk = s.length();
		if (k + sp.length() != kk) return false;
		s = s.substring(0,kkb) + s.substring(kk);
		jproject.getProject().setPersistentProperty(key,s);
		return true;
	}
	
	/** Parses an element of a serialized (encoded) String as read from the
	 * persistent property.
	 */
	public static /*@ nullable */PathItem parse(String encodedString) {
		int k = encodedString.indexOf(sep);
		if (k == -1) return null;
		String s = encodedString.substring(0,k);
		String rest = encodedString.substring(k+sep.length());
		if (s.equals(AbsolutePath.prefix)) {
			return new AbsolutePath(rest);
		} else if (s.equals(ProjectPath.prefix)) {
			return new ProjectPath(rest);
		} else if (s.equals(WorkspacePath.prefix)) {
			return new WorkspacePath(rest);
		} else if (s.equals(SpecialPath.prefix)) {
			return new SpecialPath(rest);
		} else {
			return null;
		}
	}
	
	/** Concatenates the path items into an encoded String */
	public static String concat(java.util.List<PathItem> items) {
		StringBuilder sb = new StringBuilder();
		for (PathItem i: items) {
			sb.append(i.toEncodedString());
			sb.append(PathItem.split);
		}
		if (!items.isEmpty()) sb.setLength(sb.length()-PathItem.split.length());
		return sb.toString();
	}
	
	static List<PathItem> defaultSourcePath = Arrays.asList(new PathItem[] { 
			new SpecialPath(SpecialPath.Kind.ALL_SOURCE_FOLDERS)
			});
	static List<PathItem> defaultSpecsPath = Arrays.asList(new PathItem[] { 
			new SpecialPath(SpecialPath.Kind.SOURCEPATH), 
			new SpecialPath(SpecialPath.Kind.SYSTEM_SPECS) 
			});

	
	/** Writes out the PathItem as it should be shown to the user. */
	abstract public String display();
	
	/** Writes out the PathItem as an absolute path. */
	abstract public String toAbsolute(IJavaProject jproject);
	
	/** Writes out the PathItem as it is serialized. */
	abstract public String toEncodedString();
	
	/** Returns the full serialized path for the given key and project,
	 * reading it from the property store or supplying a default. */
	static public String getEncodedPath(IJavaProject jproject, QualifiedName key) {
		try {
			String prop = jproject.getProject().getPersistentProperty(key);
			if (prop == null) {
				List<PathItem> defaults = key == PathItem.specsKey ? PathItem.defaultSpecsPath : PathItem.defaultSourcePath;
				prop = concat(defaults);
			}
			return prop;
		} catch (CoreException e) {
			// FIXME - report Error
			return ""; //$NON-NLS-1$
		}
	}
	
	/** Gets the full concatenated (with the platform dependent path separator)
	 * directory (and jar file) path (as absolute paths) for the given project and key.
	 */
	static public String getAbsolutePath(IJavaProject jproject, QualifiedName key) {
		String stored = getEncodedPath(jproject,key);
		String[] paths = stored.split(split);
		StringBuilder sb = new StringBuilder();
		for (String s: paths) {
			PathItem p  = parse(s);
			if (p == null) {
				// FIXME - error
			} else {
				sb.append(p.toAbsolute(jproject));
				sb.append(File.pathSeparator);
			}
		}
		if (paths.length != 0) sb.setLength(sb.length()-File.pathSeparator.length());
		return sb.toString();
	}

	/** Represents a simple absolute file-system path */
    public static class AbsolutePath extends PathItem {
    	
    	/** The serialized prefix identifying this kind of PathItem */
    	final static String prefix = "abs"; //$NON-NLS-1$
    	
    	/** The actual absolute file-system path */
    	protected String location;
    	
    	public AbsolutePath(String pureString) {
    		location = pureString;
    	}
    	
    	@Override
    	public String toEncodedString() {
    		return prefix + sep + location;
    	}
    	
    	@Override
    	public String toAbsolute(IJavaProject jproject) {
    		return location;
    	}
    	
    	@Override
    	public String display() {
    		return location;
    	}
    }
    
    /** Represents a path that is an element of the project */
    public static class ProjectPath extends PathItem {
 
    	final static String prefix = "prj"; //$NON-NLS-1$

    	String projectRelativeLocation;
    	
    	public ProjectPath(String projectRelativeLocation) {
    		this.projectRelativeLocation = projectRelativeLocation;
    	}
    	
    	public String toEncodedString() {
    		return prefix + sep + projectRelativeLocation;
    	}
    	
    	public String toAbsolute(IJavaProject jproject) {
    		return null;
    	}

    	public String display() {
    		return Messages.OpenJMLUI_PathItem_PROJECT + projectRelativeLocation;
    	}
    }
    
    /** Represents a path that is an element of the workspace */
    public static class WorkspacePath extends PathItem {
    	
    	final static String prefix = "wsp"; //$NON-NLS-1$
    	
    	String workspaceRelativePath;
    	
    	public WorkspacePath(String workspaceRelativePath) {
    		this.workspaceRelativePath = workspaceRelativePath;
    	}
    	
    	public String toEncodedString() {
    		return prefix + sep + workspaceRelativePath;
    	}
    	
    	public String toAbsolute(IJavaProject jproject) {
    		return ResourcesPlugin.getWorkspace().getRoot().findMember(workspaceRelativePath).getLocation().toString();
    	}

    	public String display() {
    		return Messages.OpenJMLUI_PathItem_WORKSPACE + workspaceRelativePath;
    	}
    }

    /** Represents special kinds of path items: the classpath, the sourcepath,
     * the system specs, all soursce folders. Each of these might actually be
     * multiple directories or jar files.
     */
    public static class SpecialPath extends PathItem {
    	
    	public static enum Kind {
    		// If these strings that are the first arguments are changed, then
    		// stored paths will not be properly reread
    		ALL_SOURCE_FOLDERS("sf",Messages.OpenJMLUI_PathItem_AllSourceFolders), //$NON-NLS-1$
    		CLASSPATH("cp",Messages.OpenJMLUI_PathItem_ClassPath),  //$NON-NLS-1$
    		SOURCEPATH("sp",Messages.OpenJMLUI_PathItem_SourcePath),  //$NON-NLS-1$
    		SYSTEM_SPECS("ss",Messages.OpenJMLUI_PathItem_SysSpecs); //$NON-NLS-1$
    		String key;
    		String description;
    		private Kind(String key, String d) { this.key = key; description = d; }
    	}
    	
    	final static String prefix = "spc"; //$NON-NLS-1$
    	
    	Kind kind;
    	
    	public SpecialPath(Kind kind) {
    		this.kind = kind;
    	}
    	
    	public SpecialPath(String rest) {
    		for (Kind k : Kind.values()) {
    			if (rest.equals(k.key)) { kind = k; return; }
    		}
    		// FIXME - error
    	}
    	
    	public String toEncodedString() {
    		return prefix + sep + kind.key;
    	}
    	
    	public String toAbsolute(IJavaProject jproject) {
    		String out;
    		switch (kind) {
    		case ALL_SOURCE_FOLDERS:
    			try {
    				StringBuilder sb = new StringBuilder();
    	            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    				IClasspathEntry[] entries = jproject.getResolvedClasspath(true);
    				for (IClasspathEntry cpe : entries) {
    					if (cpe.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
	                        IResource r = root.getFolder(cpe.getPath());
	                        IPath p = r.getLocation();
	                        if (p != null) {
	                        	sb.append(p.toString());
	                        	sb.append(File.pathSeparator);
	                        } else {
	                        	// FIXME - error
	                        }
    					}
    				}
    				if (sb.length() > 0) sb.setLength(sb.length() - File.pathSeparator.length());
    				out = sb.toString();
    			} catch (JavaModelException e) {
    				// FIXME - error
    				out = ""; //$NON-NLS-1$
    			}
    			break;
    			
    		case CLASSPATH:
    			StringBuilder sb = new StringBuilder();
    			List<String> cp = Activator.getDefault().utils.getClasspath(jproject);
    			for (String s : cp) {
    				sb.append(s);
    				sb.append(File.pathSeparator);
    			}
				if (sb.length() > 0) sb.setLength(sb.length() - File.pathSeparator.length());
				out = sb.toString();
    			break;
    			
    		case SOURCEPATH:
    			out = getAbsolutePath(jproject,sourceKey);
    			break;
    			
    		case SYSTEM_SPECS:
    			// Choose between internal and external specs
    			out = Activator.getDefault().utils.getInternalSystemSpecs();
    			break;
    			
    		default:
    			// FIXME - report error
    			out = ""; //$NON-NLS-1$
    		}
    		return out;
    	}
    	
    	public String display() {
    		return kind.description;
    	}
    }
}