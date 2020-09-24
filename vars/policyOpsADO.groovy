/* groovylint-disable NestedBlockDepth */
/* groovylint-disable-next-line MethodReturnTypeRequired, MethodSize, NoDef */
def call() {
  String policyName
  String policyId
  String chefWrapperId = 'ChefIdentityBuildWrapper'
  String chefJobId = 'jenkins-dbright'
  String awsWrapperId = 'aws-policyfile-archive'
  String awsWrapperRegion = 'us-east-1'
  String s3Bucket = 'dcb-policyfile-archive'
  String azureContainerName = 'policyfile-archive'
  String azureStorageCredentialsId = 'fbc18e3a-1207-4a90-9f29-765a8b88ac86'
  String gcsCredentialsId = 'gcs-policyfile-archive'
  String gcsBucket = 'gs://policyfile-archive'
  String fileIncludePattern = '*.*'
  String toUploadDir = 'toUpload'

  pipeline {
    agent any
    parameters {
        string(defaultValue: 'NOTDEFINED', name: 'BUILD_REPOSITORY_URI', description: 'Build.Repository.Uri')
    }
    environment {
        HOME = '/root/'
    }
    stages {
      stage('Get Files from ADO') {
        steps {
          checkout([
            $class: 'GitSCM',
            branches: [[name: '*/master']],
            userRemoteConfigs: [[
              name: 'origin',
              credentialsId: 'jenkins-ado',
              refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin-pull/*',
              url: "${params.BUILD_REPOSITORY_URI}"
            ]],
          ])
          sh 'ls -alt'
          echo "Build.Repository.Uri: ${params.BUILD_REPOSITORY_URI}"
        }
      }
      stage('Tests') {
        steps {
          wrap([$class: "$chefWrapperId", jobIdentity: "$chefJobId"]) {
            sh '/opt/chef-workstation/bin/cookstyle .'
            // sh "/opt/chef-workstation/bin/kitchen test"
            fileExists 'policy_groups.txt'
            fileExists 'Policyfile.rb'
          }
        }
      }
      stage('Build Policyfile Archive (.tgz)') {
        steps {
            wrap([$class: "$chefWrapperId", jobIdentity: "$chefJobId"]) {
              sh '/opt/chef-workstation/bin/chef install'
              script {
                // Let's use system commands to get values to avoid using @NonCPS (thus making our pipeline
                //  serializable) We'll get the Policy information here to use in further steps
                policyId = sh (
                  /* groovylint-disable-next-line LineLength */
                  script: '/opt/chef-workstation/bin/chef export Policyfile.lock.json ./output -a | sed -E "s/^Exported policy \'(.*)\' to.*\\/.*-(.*)\\.tgz$/\\2/"',
                  returnStdout: true
                ).trim()
                policyName = sh (
                  script: "ls ./output/*$policyId* | sed -E \"s/.*\\/(.*)-.*\$/\\1/\"",
                  returnStdout: true
                ).trim()
              }
              // Get rid of the Policyfile.lock.json for future runs
              sh 'rm Policyfile.lock.json'
              sh "mkdir $toUploadDir"
              sh "cp ./output/*$policyId* ./$toUploadDir/; cp ./policy_groups.txt ./$toUploadDir/"
            }
        }
      }
      stage('Upload Policyfile Archive to Remote Storage in AWS/GCP/Azure') {
        parallel {
          stage('Upload to GCS') {
            steps {
              dir("$toUploadDir") {
                // GCS
                googleStorageUpload(
                  credentialsId: "$gcsCredentialsId",
                  bucket: "$gcsBucket/$policyName/$policyId/",
                  pattern: "$fileIncludePattern"
                )
              }
            }
          }
          stage('Upload to S3') {
            steps {
              dir("$toUploadDir") {
                // S3
                withAWS(credentials: "$awsWrapperId", region: "$awsWrapperRegion") {
                  s3Upload(
                    bucket: "$s3Bucket",
                    path: "$policyName/$policyId/",
                    /* groovylint-disable-next-line DuplicateStringLiteral */
                    includePathPattern: "$fileIncludePattern"
                  )
                }
              }
            }
          }
          stage('Upload to Azure') {
            steps {
              dir("$toUploadDir") {
                // Azure Storage
                azureUpload(
                  storageCredentialId: "$azureStorageCredentialsId",
                  filesPath: "$fileIncludePattern",
                  storageType: 'FILE_STORAGE',
                  containerName: "$azureContainerName",
                  virtualPath: "$policyName/$policyId/"
                )
              }
            }
          }
        }
      }
      stage('Kick off Publish Job') {
        steps {
          build job: 'policyfile-publish-PFP/master', propagate: false, wait: false,
            parameters: [
                string(name: 'policyName', value: "$policyName"),
                string(name: 'policyId', value: "$policyId")
            ]
        }
      }
    }
    post {
      always {
        cleanWs()
      }
    }
  }
}
