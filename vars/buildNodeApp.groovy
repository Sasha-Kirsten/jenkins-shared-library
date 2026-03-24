// The 'call' method is what Jenkins runs when this script is triggered
def call(Map config = [:]) {
    
    // Set default values just in case the user forgets to provide them
    def dockerRepo = config.dockerRepo ?: 'unknown/app'
    // def dockerCreds = config.dockerCredentialsId ?: 'docker-hub-creds'
    def dockerCreds = config.dockerCredentialsId ?: 'a1c05e8e-d7b0-4e60-a76d-da3161734fff'
    
    pipeline {
        agent any
        
        // We can still use parameters so the user gets a dropdown in Jenkins!
        parameters {
            choice(name: 'versionBump', choices: ['patch', 'minor', 'major'], description: 'What type of version bump is this?')
        }
        
        tools {
            nodejs 'Nodejs' 
        }
        
        environment {
            // We'll calculate the new version dynamically
            PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:${env.PATH}"
        }
        
        stages {
            // 1. Checkout happens automatically in Declarative Pipelines if configured from SCM, 
            // but we can leave it if you are running this locally.
            // stage('Checkout') {
            //     steps {
            //         checkout scm
            //     }
            // }

            stage('Checkout') {
                steps {
                    // Use the exact URL, just like in your original script
                    git branch: 'main', url: config.gitUrl
                }
            }

            stage('Determine Next Version') {
                steps {
                    dir('app') {
                        script {
                            def currentVersion = sh(returnStdout: true, script: "node -p \"require('./package.json').version\"").trim()
                            def matcher = (currentVersion =~ /([a-z]?)([0-9]+)\.([0-9]+)\.([0-9]+)/)
                            
                            if (matcher) {
                                int major = matcher[0][2] as int
                                int minor = matcher[0][3] as int
                                int patch = matcher[0][4] as int

                                if (params.versionBump == "major") {
                                    env.NEW_VERSION = "${major + 1}.0.0"
                                } else if (params.versionBump == "minor") {
                                    env.NEW_VERSION = "${major}.${minor + 1}.0"
                                } else {
                                    env.NEW_VERSION = "${major}.${minor}.${patch + 1}"
                                }
                            } else {
                                error "Could not parse version string: '${currentVersion}'"
                            }
                        }
                    }
                }
            }

            stage('Upgrade Version with NPM') {
                steps {
                    dir('app') {
                        script {
                            sh "npm version ${env.NEW_VERSION} --no-git-tag-version=true"
                            sh "npm install"
                            sh "git add package.json"
                            sh "git commit --allow-empty --no-verify -m 'cicd(version-update): Updated project version to ${env.NEW_VERSION}'"
                        }
                    }
                }
            }

            stage('Create Git Tag & Push') {
                steps {
                    dir('app') {
                        sh "git tag v${env.NEW_VERSION}"
                        // sh "git push origin HEAD:${env.BRANCH_NAME} --tags"
                        sh "git push origin HEAD:main --tags"
                    }
                }
            }

            stage('Image Build') {
                steps {
                    dir('app') { 
                        // Notice we use config.dockerRepo here instead of hardcoding aleksanderk534!
                        sh "docker build --platform linux/amd64 -t ${dockerRepo}:${env.NEW_VERSION} ."
                    }
                }
            }

            stage('Push to Docker Hub') {
                steps {
                    // Notice we use config.dockerCreds here!
                    withCredentials([usernamePassword(credentialsId: "${dockerCreds}", passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
                        script {
                            sh "echo \$DOCKER_PASSWORD | docker login -u \$DOCKER_USERNAME --password-stdin"
                            sh "docker push ${dockerRepo}:${env.NEW_VERSION}"
                        }
                    }
                }
            }
        }
    }
}