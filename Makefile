help: ## Print this help
	@fgrep -h "##" $(MAKEFILE_LIST) | fgrep -v fgrep | sed -e 's/\\$$//' | sed -e 's/##//'

run:	## run containers
	docker-compose -f docker-compose.yml up &

stop:	## stop containers
	docker-compose -f docker-compose.yml down

build: 	## build artefact
	sbt test
