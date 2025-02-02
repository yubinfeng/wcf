// Import the utility functionality.

import jobs.generation.Utilities;

def project = GithubProject
def branch = GithubBranchName

// **************************
// Define the basic inner loop builds for PR 
// **************************

// Loop over the options and build up the innerloop build matrix.

[true, false].each { isPR ->
    ['Debug', 'Release'].each { configuration ->
        ['Linux', 'Windows_NT'].each { os ->
            // Calculate job name
            def osJobName = os.toLowerCase()
            if (osJobName == 'windows_nt') {
                osJobName = 'windows'
            }
            def configurationJobName = configuration.toLowerCase()
            def jobName = "${osJobName}_${configurationJobName}"
            
            def osAffinityName = os; 
            if (osAffinityName == 'Linux') {
                // our Linux runs should only ever run on Ubuntu14.04; we don't run on other flavours yet
                osAffinityName = 'Ubuntu14.04'
            }
            
            // **************************
            // Create the new commit job
            // **************************
            def newJob = null

            if (osJobName == 'linux') {
                // Jobs run as a service in unix, which means that HOME variable is not set, and it is required for restoring packages
                // so we set it first, and then call build.sh
                newJob = job(Utilities.getFullJobName(project, jobName, isPR)) {
                    steps {
                        shell("HOME=\$WORKSPACE/tempHome ./build.sh /p:ShouldCreatePackage=false /p:ShouldGenerateNuSpec=false /p:OSGroup=${os} /p:Configuration=${os}_${configuration}")
                    }
                }
            } else {
                // On other platforms, we run the build under Windows and then pack the results
                newJob = job(Utilities.getFullJobName(project, jobName, isPR)) {
                    steps {
                        // Use inline replacement
                        batchFile("build.cmd /p:Configuration=${os}_${configuration} /p:OSGroup=${os}")
                        // Pack up the results for max efficiency
                        batchFile("C:\\Packer\\Packer.exe .\\bin\\build.pack .\\bin")
                    }
                }
            }
            
            Utilities.setMachineAffinity(newJob, osAffinityName, 'latest-or-auto')
            Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")
            Utilities.addXUnitDotNETResults(newJob, 'bin/tests/**/testResults.xml')
            if (os != 'Linux') {
                // We do not do the pack step on non-Linux builds
                Utilities.addArchival(newJob, "bin/${os}.AnyCPU.${configuration}/**,bin/build.pack")
            }
            if (isPR) {
                Utilities.addGithubPRTriggerForBranch(newJob, branch, "Innerloop ${os} ${configuration} Build and Test")
            }
            else {
                Utilities.addGithubPushTrigger(newJob)
            }
        }
    }
}

// **************************
// Define the code coverage jobs
// **************************

// Define build string
def codeCoverageBuildString = '''build.cmd /p:ShouldCreatePackage=false /p:ShouldGenerateNuSpec=false /p:OSGroup=Windows_NT /p:Configuration=Windows_NT_Debug /p:Coverage=true /p:WithCategories=\"InnerLoop;OuterLoop\"'''

// Generate a rolling (12 hr job) and a PR job that can be run on demand
[true, false].each { isPR ->
    def newJob = job(Utilities.getFullJobName(project, 'code_coverage_windows', isPR)) {
      label('windows-elevated')
      steps {
        batchFile(codeCoverageBuildString)
      }
    }
    
    Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")
    Utilities.addHtmlPublisher(newJob, 'bin/tests/coverage', 'Code Coverage Report', 'index.htm')
    Utilities.addArchival(newJob, '**/coverage/*,msbuild.log')
    
    if (isPR) {
        Utilities.addGithubPRTriggerForBranch(newJob, branch, 'Code Coverage Windows Debug', '(?i).*test\\W+code\\W*coverage.*')
    }
    else {
        Utilities.addPeriodicTrigger(newJob, '@daily')
    }
}

// **************************
// Outerloop.  Rolling every 4 hours for debug and release
// **************************

[true, false].each { isPR ->
    ['Debug', 'Release'].each { configuration ->
        def configurationJobName = configuration.toLowerCase()
        def jobName = "outerloop_windows_${configurationJobName}"
        
        // Create the new rolling job
        def newJob = job(Utilities.getFullJobName(project, jobName, isPR)) {
            label('windows-elevated')
            steps {
                batchFile("build.cmd /p:Configuration=Windows_NT_${configuration} /p:WithCategories=OuterLoop")
            }
        }

        // Add commit job options
        Utilities.standardJobSetup(newJob, project, isPR, "*/${branch}")
        Utilities.addXUnitDotNETResults(newJob, 'bin/tests/**/testResults.xml')
        
        if (isPR) {
            Utilities.addGithubPRTriggerForBranch(newJob, branch, "Outerloop Windows ${configuration} Build and Test", '(?i).*test\\W+outerloop.*')
        }
        else {
            Utilities.addPeriodicTrigger(newJob, 'H H/4 * * *')
        }
    }
}
