package org.codehaus.mojo.appassembler;

/**
 * The MIT License
 *
 * Copyright 2005-2006 The Codehaus.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.repository.layout.LegacyRepositoryLayout;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.appassembler.daemon.DaemonGeneratorException;
import org.codehaus.mojo.appassembler.daemon.script.ScriptGenerator;
import org.codehaus.mojo.appassembler.model.*;
import org.codehaus.mojo.appassembler.model.JvmSettings;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Assembles the artifacts and generates bin scripts for the configured applications
 *
 * @goal assemble
 * @requiresDependencyResolution runtime
 * @phase package
 *
 * @author <a href="mailto:kristian.nordal@gmail.com">Kristian Nordal</a>
 * @version $Id$
 */
public class AssembleMojo
    extends AbstractMojo
{
    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /**
     * @readonly
     * @parameter expression="${project}"
     */
    private MavenProject mavenProject;

    /**
     * @readonly
     * @parameter expression="${project.build.directory}"
     */
    private String buildDirectory;

    /**
     * @readonly
     * @parameter expression="${project.runtimeArtifacts}"
     */
    private List artifacts;

    /**
     * The directory that will be used to assemble the artifacts in
     * and place the bin scripts.
     *
     * @required
     * @parameter expression="${assembleDirectory}" default-value="${project.build.directory}/appassembler"
     */
    private File assembleDirectory;

    /**
     * @readonly
     * @parameter expression="${project.artifact}"
     */
    private Artifact projectArtifact;

    /**
     * @readonly
     * @parameter expression="${localRepository}"
     */
    private ArtifactRepository localRepository;

    /**
     * The set of Programs that bin files will be generated for.
     *
     * @required
     * @parameter
     */
    private Set programs;

    /**
     * Prefix generated bin files with this.
     *
     * @parameter
     */
    private String binPrefix;

    /**
     * Include /etc in the beginning of the classpath in the generated bin files
     *
     * @parameter default-value="true"
     */
    private boolean includeConfigurationDirectoryInClasspath;

    /**
     * The layout of the generated Maven repository.
     * Supported types - "default" (Maven2) | "legacy" (Maven1)
     *
     * @parameter default-value="default"
     */
    private String repositoryLayout;

    /**
     * Extra arguments that will be given to the JVM verbatim.
     *
     * @parameter 
     */
    private String extraJvmArguments;

    /**
     * The default platforms the plugin will generate bin files for.
     * Configure with string values - "all"(default/empty) | "windows" | "unix"
     *
     * @parameter
     */
    private Set platforms;

    // -----------------------------------------------------------------------
    // Components
    // -----------------------------------------------------------------------

    /**
     * @component org.apache.maven.artifact.repository.ArtifactRepositoryFactory
     */
    private ArtifactRepositoryFactory artifactRepositoryFactory;

    /**
     * @component org.apache.maven.artifact.installer.ArtifactInstaller
     */
    private ArtifactInstaller artifactInstaller;

    /**
     * @component org.codehaus.mojo.appassembler.daemon.script.ScriptGenerator
     */
    private ScriptGenerator scriptGenerator;

    // -----------------------------------------------------------------------
    //
    // -----------------------------------------------------------------------

    /**
     * The layout of the repository.
     */
    private ArtifactRepositoryLayout artifactRepositoryLayout;

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    // This set will be used unless the program descriptor has a set of platforms
    private Set defaultPlatforms;

    private final static Set VALID_PLATFORMS;

    static
    {
        Set set = new HashSet();
        set.add( "unix" );
        set.add( "windows" );

        VALID_PLATFORMS = Collections.unmodifiableSet( set );
    }

    // ----------------------------------------------------------------------
    // Validate
    // ----------------------------------------------------------------------

    public void validate()
        throws MojoFailureException, MojoExecutionException
    {
        // ----------------------------------------------------------------------
        // Create new repository for dependencies
        // ----------------------------------------------------------------------

        if ( repositoryLayout == null || repositoryLayout.equals( "default" ) )
        {
            artifactRepositoryLayout = new DefaultRepositoryLayout();
        }
        else if ( repositoryLayout.equals( "legacy" ) )
        {
            artifactRepositoryLayout = new LegacyRepositoryLayout();
        }
        else
        {
            throw new MojoFailureException( "Unknown repository layout '" + repositoryLayout + "'." );
        }

        // ----------------------------------------------------------------------
        // Validate default platform configuration
        // ----------------------------------------------------------------------

        defaultPlatforms = validatePlatforms( platforms, VALID_PLATFORMS );

        // ----------------------------------------------------------------------
        // Validate Programs
        // ----------------------------------------------------------------------

        for ( Iterator i = programs.iterator(); i.hasNext(); )
        {
            Program program = (Program) i.next();

            if ( program.getMainClass() == null || program.getMainClass().trim().equals( "" ) )
            {
                throw new MojoFailureException( "Missing main class in Program configuration" );
            }

            // platforms
            program.setPlatforms( validatePlatforms( program.getPlatforms(), defaultPlatforms ) );
        }
    }

    // ----------------------------------------------------------------------
    // Execute
    // ----------------------------------------------------------------------

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        // validate input and set defaults
        validate();

        // ----------------------------------------------------------------------
        // Install dependencies in the new repository
        // ----------------------------------------------------------------------

        // The repo where the jar files will be installed
        ArtifactRepository artifactRepository = artifactRepositoryFactory.createDeploymentArtifactRepository(
            "appassembler", "file://" + assembleDirectory.getAbsolutePath() + "/repo", artifactRepositoryLayout,
            false );

        for ( Iterator it = artifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();

            installArtifact( artifactRepository, artifact );
        }

        // install the project's artifact in the new repository
        installArtifact( artifactRepository, projectArtifact );

        // ----------------------------------------------------------------------
        // Setup
        // ----------------------------------------------------------------------

        setUpWorkingArea();

        // ----------------------------------------------------------------------
        // Create bin files
        // ----------------------------------------------------------------------

        for ( Iterator it = programs.iterator(); it.hasNext(); )
        {
            Program program = (Program) it.next();

            Set platforms = validatePlatforms( program.getPlatforms(), defaultPlatforms );

            for ( Iterator platformIt = platforms.iterator(); platformIt.hasNext(); )
            {
                String platform = (String) platformIt.next();

                try
                {
                    scriptGenerator.createBinScript( platform,
                                                     programToDaemon( program ),
                                                     assembleDirectory );
                }
                catch ( DaemonGeneratorException e )
                {
                    throw new MojoExecutionException( "Error while generating script for the program '" +
                        program.getName() + "' for the platform '" + platform + "'." );
                }
            }
        }
    }

    private org.codehaus.mojo.appassembler.model.Daemon programToDaemon( Program program )
    {
        org.codehaus.mojo.appassembler.model.Daemon daemon = new org.codehaus.mojo.appassembler.model.Daemon();

        daemon.setId( program.getName() );
        daemon.setMainClass( program.getMainClass() );

        List classpath = new ArrayList();

        if ( includeConfigurationDirectoryInClasspath )
        {
            Directory directory = new Directory();
            directory.setRelativePath( "etc" );
            classpath.add( directory  );
        }

        Set classPathArtifacts = new HashSet( artifacts );
        classPathArtifacts.add( projectArtifact );

        for ( Iterator it = classPathArtifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();
            Dependency dependency = new Dependency();
            dependency.setGroupId( artifact.getGroupId() );
            dependency.setArtifactId( artifact.getArtifactId() );
            dependency.setVersion( artifact.getVersion() );
            dependency.setRelativePath( artifactRepositoryLayout.pathOf( artifact ) );
            classpath.add( dependency );
        }

        daemon.setClasspath( classpath );

        // -----------------------------------------------------------------------
        // This is a bit of a clusterfuck
        // -----------------------------------------------------------------------

        JvmSettings jvmSettings = new JvmSettings();
        
        if ( extraJvmArguments != null )
        {
            jvmSettings.setExtraArguments( parseTokens( this.extraJvmArguments ) );
        }
        
        daemon.setJvmSettings( jvmSettings );

        return daemon;
    }

    // ----------------------------------------------------------------------
    // Install artifacts into the assemble repository
    // ----------------------------------------------------------------------

    private void installArtifact( ArtifactRepository artifactRepository, Artifact artifact )
        throws MojoExecutionException
    {
        try
        {
            // Necessary for the artifact's baseVersion to be set correctly
            // See: http://mail-archives.apache.org/mod_mbox/maven-dev/200511.mbox/%3c437288F4.4080003@apache.org%3e
            artifact.isSnapshot();

            artifactInstaller.install( artifact.getFile(), artifact, artifactRepository );
        }
        catch ( ArtifactInstallationException e )
        {
            throw new MojoExecutionException( "Failed to copy artifact.", e );
        }
    }

    // ----------------------------------------------------------------------
    // Set up the assemble environment
    // ----------------------------------------------------------------------

    private void setUpWorkingArea()
        throws MojoFailureException
    {
        // create (if necessary) directory for bin files
        File binDir = new File( assembleDirectory.getAbsolutePath(), "bin" );

        if ( !binDir.exists() )
        {
            boolean success = new File( assembleDirectory.getAbsolutePath(), "bin" ).mkdir();

            if ( !success )
            {
                throw new MojoFailureException( "Failed to create directory for bin files." );
            }
        }
    }

    private Set validatePlatforms( Set platforms, Set defaultPlatforms )
        throws MojoFailureException
    {
        if ( platforms == null )
        {
            return defaultPlatforms;
        }

        if ( platforms.size() == 1 && platforms.iterator().next().equals( "all" ) )
        {
            return VALID_PLATFORMS;
        }

        if ( !VALID_PLATFORMS.containsAll( platforms ) )
        {
            throw new MojoFailureException( "Non-valid default platform declared, supported types are: " + VALID_PLATFORMS );
        }

        return platforms;
    }

    public static List parseTokens( String arg )
    {
        List extraJvmArguments = new ArrayList();

        if ( StringUtils.isEmpty( arg ) )
        {
            return extraJvmArguments;
        }

        StringTokenizer tokenizer = new StringTokenizer( arg );

        String argument = null;

        while( tokenizer.hasMoreTokens() )
        {
            String token = tokenizer.nextToken();

            if ( argument != null )
            {
                if ( token.length() == 0 )
                {
                    // ignore it
                    continue;
                }

                int length = token.length();

                if ( token.charAt( length - 1 ) == '\"' )
                {
                    extraJvmArguments.add( argument + " " + token.substring( 0, length - 1 ) );
                    argument = null;
                }
                else
                {
                    argument += " " + token;
                }
            }
            else
            {
                // If the token starts with a ", save it
                if ( token.charAt( 0 ) == '\"' )
                {
                    argument = token.substring( 1 ) ;
                }
                else
                {
                    extraJvmArguments.add( token );
                }
            }
        }

        return extraJvmArguments;
    }
}
