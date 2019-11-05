#!/bin/bash
baseDir=`pwd`
echo $baseDir
for projects in `ls $baseDir`
do
   projectsDir=$baseDir"/"$projects
   if [ -d "$projectsDir" ] ; then 
   	cd $projectsDir
  	pwd
   	ls -a | grep -q -w ".git"
   	result=$?
	if [ $result -eq 0  ] ; then
   	  git pull
	fi
   fi
done
